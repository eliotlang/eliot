package com.vanillasource.eliot.eliotc.visualization

import cats.effect.IO
import FactVisualizationTracker.GraphData
import com.vanillasource.eliot.eliotc.design.DesignTokens
import com.vanillasource.eliot.eliotc.feedback.Logging
import com.vanillasource.eliot.eliotc.processor.CompilerFactKey

import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable

/** Tracks fact generation and consumption between processors to build a visualization graph. Nodes represent
  * processors, edges represent fact types flowing between them.
  */
final class FactVisualizationTracker(
    // Mapping: FactTypeName -> (Processors that were requested, Processors that just produced without being asked)
    factProducers: AtomicReference[Map[String, (Set[String], Set[String])]],
    // Mapping: (Processor, FactTypeName) -> how many times that processor requested that kind of fact. Counted rather
    // than listed: a request is recorded on every single fact read of a compilation, so anything that grows with the
    // number of requests (an append to a list, notably) makes the whole compiler quadratic in the size of the program.
    factRequests: AtomicReference[Map[(String, String), Int]],
    // Keys that were requested up until now (for determining whether fact was asked for)
    factRequestedKeys: AtomicReference[Set[CompilerFactKey[?]]]
) extends Logging {

  /** Record that a processor produced a fact. It runs on the compilation's thread, inside the fact's generation. */
  def recordFactProduction(processorName: String, factKey: CompilerFactKey[?]): Unit = {
    val factTypeName = getFactTypeName(factKey)
    // A key requested before is a processor fulfilling a request; any other starts a new workflow of sorts.
    val requested    = factRequestedKeys.get().contains(factKey)
    factProducers.updateAndGet(
      _.updatedWith(factTypeName) { processorsMaybe =>
        val processors = processorsMaybe.getOrElse((Set.empty, Set.empty))

        Some(if (requested) (processors._1 + processorName, processors._2) else (processors._1, processors._2 + processorName))
      }
    ): Unit
  }

  /** Record that a processor requested a fact. It runs on the compilation's thread, inside the reader's generation. */
  def recordFactRequest(processorName: String, factKey: CompilerFactKey[?]): Unit = {
    val factTypeName = getFactTypeName(factKey)
    factRequestedKeys.updateAndGet(_ + factKey)
    factRequests.updateAndGet(_.updatedWith((processorName, factTypeName))(count => Some(count.getOrElse(0) + 1))): Unit
  }

  /** Generate HTML visualization using Cytoscape.js */
  def generateVisualization(outputPath: Path): IO[Unit] =
    for {
      producers <- IO.delay(factProducers.get())
      requests  <- IO.delay(factRequests.get())
      graphData <- buildGraphData(producers, requests)
      html       = generateHTML(graphData)
      _         <- IO.blocking(Files.writeString(outputPath, html))
      _         <- info[IO](s"Fact visualization saved to: $outputPath")
    } yield ()

  private def buildGraphData(
      producers: Map[String, (Set[String], Set[String])],
      requests: Map[(String, String), Int]
  ): IO[GraphData] = IO {
    val edges = mutable.Map[(String, String, String), Int]()
    val nodes = mutable.Set[String]()

    // Build edges from requests: fact flows from producer to consumer
    requests.foreach { case ((consumer, factType), count) =>
      producers.get(factType).foreach { producerSets =>
        producerSets._1.foreach { producer =>
          if (producer != consumer) {
            val key = (producer, consumer, factType)
            edges(key) = edges.getOrElse(key, 0) + count
            nodes += producer
            nodes += consumer
          }
        }
      }
    }

    // Add nodes that only produce facts (no consumers yet)
    producers.values.map(_._1).flatten.foreach(nodes += _)

    GraphData(nodes.toSet, edges.toMap)
  }

  private def getFactTypeName(factKey: CompilerFactKey[?]): String = {
    val fullName = factKey.getClass.getName
    // Remove package prefix and $Key suffix for cleaner display
    fullName
      .replaceAll(".*\\.", "")
      .replaceAll("\\$Key$", "")
      .replaceAll("\\$", ".")
  }

  private def generateHTML(graphData: GraphData): String = {
    // Calculate statistics for dynamic scaling
    val counts   = graphData.edges.values.toSeq
    val minCount = if (counts.isEmpty) 1 else counts.min
    val maxCount = if (counts.isEmpty) 1 else counts.max
    val logMin   = math.log(minCount.toDouble)
    val logMax   = math.log(maxCount.toDouble)

    // Calculate ranks first (shortest path from sources), then derive back-edges from ranks
    val ranks     = calculateRanks(graphData)
    val backEdges = findBackEdges(graphData, ranks)

    val nodeElements = graphData.nodes.toSeq.sorted.zipWithIndex
      .map { case (name, idx) =>
        val rank = ranks.getOrElse(name, 0)
        s"""{ data: { id: '$name', label: '${simplifyProcessorName(name)}', rank: $rank } }"""
      }
      .mkString(",\n            ")

    val edgeElements = graphData.edges.toSeq
      .sortBy { case ((producer, _, _), _) => producer }
      .zipWithIndex
      .map { case (((producer, consumer, factType), count), idx) =>
        val edgeId        = s"edge_$idx"
        // Exponential scaling: map count logarithmically to edge width (1-10 range)
        val logCount      = math.log(count.toDouble)
        val normalizedLog = if (logMax > logMin) (logCount - logMin) / (logMax - logMin) else 0.5
        val edgeWidth     = 1 + (normalizedLog * 9) // Scale to 1-10 range

        val isBackEdge = backEdges.contains((producer, consumer, factType))

        s"""{ data: { id: '$edgeId', source: '$producer', target: '$consumer', label: '${simplifyFactName(
            factType
          )}', count: $count, width: ${edgeWidth.round}, backEdge: $isBackEdge } }"""
      }
      .mkString(",\n            ")

    s"""<!DOCTYPE html>
<html lang="en">
<head>
    <title>Fact generation flow · Eliot</title>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <link rel="icon" href="${DesignTokens.favicon}">
    <style>
${DesignTokens.tokensCss}

/* ======================================================================
 * fact-flow visualizer components — the brand's light DOCS surface, matching
 * apidoc. `.on-paper` on <body> flips the semantic tokens (--bg/--surface/
 * --text/--border/...) to the light scope for the page chrome, and the graph
 * canvas (#cy) is light too: a warm paper well with white processor nodes,
 * signal-green fact edges, and red dashed back-edges. Reference only the
 * semantic / base-palette tokens above, so a brand change flows in by
 * re-syncing the shared tokens. The Cytoscape canvas can't read CSS variables,
 * so the graph script reads the resolved light :root tokens at runtime (see
 * below) and styles nodes/edges to match — the semantic --bg/--surface live
 * under the body's .on-paper scope, out of the script's :root reach, so it
 * reads the light base palette (--paper-bg/--paper-raised/--ink-text/...).
 * ====================================================================== */
html, body { height: 100%; }
body { margin: 0; display: flex; flex-direction: column; background: var(--bg); color: var(--text); font-family: var(--font-body); }
#header { padding: 18px 28px; background: var(--surface); border-bottom: 1px solid var(--border); }
#header .brand { display: inline-flex; align-items: center; gap: 6px; font-family: var(--font-display); font-weight: var(--weight-medium); font-size: 1.05rem; color: var(--text); letter-spacing: var(--tracking-tight); margin-bottom: 10px; }
#header .brand .tag { color: var(--text-muted); font-weight: var(--weight-regular); font-size: .6rem; letter-spacing: var(--tracking-label); text-transform: uppercase; margin-left: 2px; }
#header h1 { font-family: var(--font-display); font-weight: var(--weight-medium); font-size: 1.5rem; color: var(--text); letter-spacing: var(--tracking-tight); margin: 0 0 6px; }
#header p { margin: 0; color: var(--text-muted); font-size: var(--text-sm); }
#cy { flex: 1; min-height: 0; background: var(--paper-bg); margin: 16px; border: 1px solid var(--border); border-radius: var(--radius-lg); }
.panel { position: absolute; background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius-md); box-shadow: var(--shadow-md); color: var(--text); }
#info-panel { top: 124px; right: 36px; padding: 18px; max-width: 300px; display: none; }
#info-panel h3 { font-family: var(--font-display); font-weight: var(--weight-medium); font-size: var(--text-h4); margin: 0 0 10px; color: var(--text); }
#info-panel p { margin: 5px 0; color: var(--text-muted); font-size: var(--text-sm); }
#info-panel strong { color: var(--text); font-weight: var(--weight-medium); }
.legend { bottom: 36px; left: 36px; padding: 14px 16px; }
.legend h4 { font-family: var(--font-display); font-weight: var(--weight-medium); font-size: var(--text-xs); margin: 0 0 10px; color: var(--text); text-transform: uppercase; letter-spacing: var(--tracking-label); }
.legend-item { display: flex; align-items: center; gap: 10px; margin: 6px 0; font-size: var(--text-xs); color: var(--text-muted); }
.legend-swatch { width: 18px; height: 14px; border-radius: var(--radius-xs); flex: 0 0 auto; }
.legend-line { width: 26px; height: 0; flex: 0 0 auto; }
    </style>
    <script src="https://cdnjs.cloudflare.com/ajax/libs/cytoscape/3.23.0/cytoscape.min.js"></script>
    <script src="https://unpkg.com/dagre@0.8.5/dist/dagre.min.js"></script>
    <script src="https://cdn.jsdelivr.net/npm/cytoscape-dagre@2.5.0/cytoscape-dagre.min.js"></script>
</head>
<body class="on-paper">
    <div id="header">
        <div class="brand">eliot<span class="eliot-cursor"></span><span class="tag">compiler</span></div>
        <h1>Fact generation flow</h1>
        <p>How facts flow between compiler processors. Click a node or edge for details.</p>
    </div>
    <div id="cy"></div>
    <div id="info-panel" class="panel"></div>
    <div class="legend panel">
        <h4>Legend</h4>
        <div class="legend-item">
            <span class="legend-swatch" style="background: var(--surface); border: 1px solid var(--border-strong);"></span>
            <span>Processor</span>
        </div>
        <div class="legend-item">
            <span class="legend-line" style="border-top: 3px solid var(--green-dim);"></span>
            <span>Fact flow</span>
        </div>
        <div class="legend-item">
            <span class="legend-line" style="border-top: 3px dashed var(--fail);"></span>
            <span>Back edge (cycle)</span>
        </div>
    </div>
    <script>
        var rootStyle = getComputedStyle(document.documentElement);
        function tok(name){ return rootStyle.getPropertyValue(name).trim(); }
        // Light docs palette, read straight from the :root base tokens (the semantic
        // --bg/--surface/... live under the body's .on-paper scope, out of reach here).
        var PAPER_BG = tok('--paper-bg'), PAPER_RAISED = tok('--paper-raised'),
            PAPER_LINE_STRONG = tok('--paper-line-strong'),
            INK_TEXT = tok('--ink-text'), INK_MUTED = tok('--ink-muted'),
            GREEN = tok('--green'), GREEN_DIM = tok('--green-dim'),
            FAIL = tok('--fail');
        var MONO = "JetBrains Mono, ui-monospace, monospace";
        var cy = cytoscape({
            container: document.getElementById('cy'),

            elements: [
            $nodeElements,
            $edgeElements
            ],

            style: [
                {
                    selector: 'node',
                    style: {
                        'shape': 'round-rectangle',
                        'background-color': PAPER_RAISED,
                        'label': 'data(label)',
                        'color': INK_TEXT,
                        'text-valign': 'center',
                        'text-halign': 'center',
                        'font-family': MONO,
                        'font-size': '11px',
                        'font-weight': 'bold',
                        'width': '120px',
                        'height': '60px',
                        'border-width': 1.5,
                        'border-color': PAPER_LINE_STRONG,
                        'text-wrap': 'wrap',
                        'text-max-width': '104px'
                    }
                },
                {
                    selector: 'node:selected',
                    style: {
                        'color': GREEN_DIM,
                        'border-color': GREEN,
                        'border-width': 2.5
                    }
                },
                {
                    selector: 'edge',
                    style: {
                        'width': 'data(width)',
                        'line-color': GREEN_DIM,
                        'target-arrow-color': GREEN_DIM,
                        'target-arrow-shape': 'triangle',
                        'curve-style': 'bezier',
                        'label': 'data(label)',
                        'font-family': MONO,
                        'font-size': '9px',
                        'text-rotation': 'autorotate',
                        'text-margin-y': -10,
                        'color': INK_MUTED,
                        'text-background-color': PAPER_BG,
                        'text-background-opacity': 0.85,
                        'text-background-padding': '3px',
                        'arrow-scale': 1.1
                    }
                },
                {
                    selector: 'edge[?backEdge]',
                    style: {
                        'line-color': FAIL,
                        'target-arrow-color': FAIL,
                        'line-style': 'dashed',
                        'line-dash-pattern': [6, 3]
                    }
                },
                {
                    selector: 'edge:selected',
                    style: {
                        'line-color': GREEN,
                        'target-arrow-color': GREEN,
                        'width': 'data(width)'
                    }
                }
            ],

            layout: {
                name: 'dagre',
                rankDir: 'LR',
                nodeSep: 100,
                edgeSep: 50,
                rankSep: 150,
                animate: true,
                animationDuration: 500,
                fit: true,
                padding: 50,
                // Use explicit ranks from our calculation
                rank: function(node) {
                    return node.data('rank');
                },
                // Ignore back-edges for ranking by setting their minimum length to 0
                minLen: function(edge) {
                    return edge.data('backEdge') ? 0 : 1;
                }
            }
        });

        // Interaction handlers
        var infoPanel = document.getElementById('info-panel');

        cy.on('tap', 'node', function(evt){
            var node = evt.target;
            var incoming = node.incomers('edge');
            var outgoing = node.outgoers('edge');

            var incomingFacts = incoming.map(e => e.data('label') + ' (' + e.data('count') + ')').join(', ') || 'None';
            var outgoingFacts = outgoing.map(e => e.data('label') + ' (' + e.data('count') + ')').join(', ') || 'None';

            infoPanel.innerHTML = '<h3>' + node.data('label') + '</h3>' +
                '<p><strong>Consumes:</strong> ' + incomingFacts + '</p>' +
                '<p><strong>Produces:</strong> ' + outgoingFacts + '</p>' +
                '<p><strong>Rank:</strong> ' + node.data('rank') + '</p>' +
                '<p><strong>Total In:</strong> ' + incoming.length + ' edges</p>' +
                '<p><strong>Total Out:</strong> ' + outgoing.length + ' edges</p>';
            infoPanel.style.display = 'block';
        });

        cy.on('tap', 'edge', function(evt){
            var edge = evt.target;
            var backEdgeInfo = edge.data('backEdge') ?
                '<p><strong>Type:</strong> <span style="color:' + FAIL + ';">Back edge (creates cycle)</span></p>' :
                '<p><strong>Type:</strong> Forward edge</p>';
            infoPanel.innerHTML = '<h3>' + edge.data('label') + '</h3>' +
                '<p><strong>From:</strong> ' + edge.source().data('label') + '</p>' +
                '<p><strong>To:</strong> ' + edge.target().data('label') + '</p>' +
                '<p><strong>Count:</strong> ' + edge.data('count') + ' fact(s)</p>' +
                backEdgeInfo;
            infoPanel.style.display = 'block';
        });

        cy.on('tap', function(evt){
            if (evt.target === cy) {
                infoPanel.style.display = 'none';
            }
        });

        // Fit and center the graph
        cy.fit();
        cy.center();
    </script>
</body>
</html>"""
  }

  /** Find back-edges based on node ranks. A back-edge is an edge that goes from a node to another node with the same or
    * lower rank (i.e., not moving forward in the DAG).
    */
  private def findBackEdges(graphData: GraphData, ranks: Map[String, Int]): Set[(String, String, String)] =
    graphData.edges.keys.filter { case (source, target, _) =>
      ranks.getOrElse(source, 0) >= ranks.getOrElse(target, 0)
    }.toSet

  /** Calculate the rank of each node as the longest directed path from any source node (nodes with no incoming edges).
    * Uses topological sort with dynamic programming. Nodes in cycles unreachable from sources get rank 0.
    */
  private def calculateRanks(graphData: GraphData): Map[String, Int] = {
    val outgoingEdges = graphData.edges.keys.groupBy(_._1).map((k, v) => k -> v.map(_._2))
    val incomingEdges = graphData.edges.keys.groupBy(_._2).map((k, v) => k -> v.map(_._1))

    // Find source nodes (no incoming edges)
    val sources = graphData.nodes.filter(node => !incomingEdges.contains(node))

    // Topological sort with longest path calculation (Kahn's algorithm)
    val ranks     = mutable.Map[String, Int]()
    val queue     = mutable.Queue[String]()
    val inDegree  = mutable.Map[String, Int]()
    val maxInRank = mutable.Map[String, Int]()

    // Initialize in-degrees and max incoming ranks
    graphData.nodes.foreach { node =>
      inDegree(node) = incomingEdges.getOrElse(node, Set.empty).size
      maxInRank(node) = -1
    }

    // Start from all sources at rank 0
    sources.foreach { source =>
      ranks(source) = 0
      queue.enqueue(source)
    }

    while (queue.nonEmpty) {
      val node     = queue.dequeue()
      val nodeRank = ranks(node)

      outgoingEdges.getOrElse(node, Set.empty).foreach { target =>
        maxInRank(target) = math.max(maxInRank(target), nodeRank)
        inDegree(target) -= 1

        if (inDegree(target) == 0) {
          ranks(target) = maxInRank(target) + 1
          queue.enqueue(target)
        }
      }
    }

    // Handle any unranked nodes (in cycles with no path from sources)
    graphData.nodes.foreach { node =>
      if (!ranks.contains(node)) {
        ranks(node) = 0
      }
    }

    ranks.toMap
  }

  private def simplifyProcessorName(fullName: String): String = {
    fullName
      .replaceAll(".*\\.", "")
      .replaceAll("Processor$", "")
      .replaceAll("([a-z])([A-Z])", "$1\\\\n$2") // Add escaped line breaks for readability
  }

  private def simplifyFactName(fullName: String): String = {
    fullName.replaceAll("([a-z])([A-Z])", "$1 $2")
  }
}

object FactVisualizationTracker {
  case class GraphData(nodes: Set[String], edges: Map[(String, String, String), Int])

  def create(): IO[FactVisualizationTracker] =
    IO.delay(new FactVisualizationTracker(AtomicReference(Map.empty), AtomicReference(Map.empty), AtomicReference(Set.empty)))
}
