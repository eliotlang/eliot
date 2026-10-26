package com.vanillasource.eliot.eliotc.processor.common

import cats.Monad
import cats.data.Kleisli
import cats.effect.IO
import cats.effect.std.AtomicCell
import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.processor.ProcessorTest
import com.vanillasource.eliot.eliotc.processor.ProcessorTest.*
import com.vanillasource.eliot.eliotc.processor.{CompilerFactKey, CompilerProcessor}

class SequentialCompilerProcessorsTest extends ProcessorTest {
  val testKey = TestFactKey("test")

  "sequential compiler processors" should "run all processors when all succeed" in {
    val executionTracker = AtomicCell[IO].of(List.empty[Int])

    executionTracker.flatMap { tracker =>
      val processor1 = trackingProcessor(1, tracker, success = true)
      val processor2 = trackingProcessor(2, tracker, success = true)
      val processor3 = trackingProcessor(3, tracker, success = true)
      val sequential = new SequentialCompilerProcessors(Seq(processor1, processor2, processor3))

      runCompilerIO {
        sequential.generate(testKey)
      }.flatMap { result =>
        tracker.get.map { executionOrder =>
          val _ = result shouldBe Right(())
          executionOrder shouldBe List(1, 2, 3)
        }
      }
    }
  }

  it should "run all processors even when first processor fails" in {
    val executionTracker = AtomicCell[IO].of(List.empty[Int])

    executionTracker.flatMap { tracker =>
      val processor1 = trackingProcessor(1, tracker, success = false)
      val processor2 = trackingProcessor(2, tracker, success = true)
      val processor3 = trackingProcessor(3, tracker, success = true)
      val sequential = new SequentialCompilerProcessors(Seq(processor1, processor2, processor3))

      runCompilerIO {
        sequential.generate(testKey)
      }.flatMap { result =>
        tracker.get.map { executionOrder =>
          executionOrder shouldBe List(1, 2, 3)
        }
      }
    }
  }

  it should "run all processors even when middle processor fails" in {
    val executionTracker = AtomicCell[IO].of(List.empty[Int])

    executionTracker.flatMap { tracker =>
      val processor1 = trackingProcessor(1, tracker, success = true)
      val processor2 = trackingProcessor(2, tracker, success = false)
      val processor3 = trackingProcessor(3, tracker, success = true)
      val sequential = new SequentialCompilerProcessors(Seq(processor1, processor2, processor3))

      runCompilerIO {
        sequential.generate(testKey)
      }.flatMap { result =>
        tracker.get.map { executionOrder =>
          executionOrder shouldBe List(1, 2, 3)
        }
      }
    }
  }

  it should "run all processors even when last processor fails" in {
    val executionTracker = AtomicCell[IO].of(List.empty[Int])

    executionTracker.flatMap { tracker =>
      val processor1 = trackingProcessor(1, tracker, success = true)
      val processor2 = trackingProcessor(2, tracker, success = true)
      val processor3 = trackingProcessor(3, tracker, success = false)
      val sequential = new SequentialCompilerProcessors(Seq(processor1, processor2, processor3))

      runCompilerIO {
        sequential.generate(testKey)
      }.flatMap { result =>
        tracker.get.map { executionOrder =>
          executionOrder shouldBe List(1, 2, 3)
        }
      }
    }
  }

  it should "collect all errors from all processors" in {
    val processor1 = failingProcessor("error1")
    val processor2 = failingProcessor("error2")
    val processor3 = failingProcessor("error3")
    val sequential = new SequentialCompilerProcessors(Seq(processor1, processor2, processor3))

    runCompilerIO {
      sequential.generate(testKey) >> currentErrors
    }.asserting(_.map(_.toList.map(_.message)) shouldBe Right(List("error1", "error2", "error3")))
  }

  it should "collect errors from failing processors while running successful ones" in {
    val processor1 = failingProcessor("error1")
    val processor2 = successfulProcessor()
    val processor3 = failingProcessor("error3")
    val sequential = new SequentialCompilerProcessors(Seq(processor1, processor2, processor3))

    runCompilerIO {
      sequential.generate(testKey) >> currentErrors
    }.asserting(_.map(_.toList.map(_.message)) shouldBe Right(List("error1", "error3")))
  }

  it should "succeed when all processors succeed" in {
    val processor1 = successfulProcessor()
    val processor2 = successfulProcessor()
    val processor3 = successfulProcessor()
    val sequential = new SequentialCompilerProcessors(Seq(processor1, processor2, processor3))

    runCompilerIO {
      sequential.generate(testKey)
    }.asserting(_ shouldBe Right(()))
  }

  it should "not invoke a processor that does not handle the key" in {
    AtomicCell[IO].of(List.empty[Int]).flatMap { tracker =>
      val sequential = new SequentialCompilerProcessors(
        Seq(notHandlingProcessor(1, tracker), trackingProcessor(2, tracker, success = true))
      )

      runCompilerIO(sequential.generate(testKey)) >> tracker.get.asserting(_ shouldBe List(2))
    }
  }

  it should "handle a key that any of its processors handles" in {
    AtomicCell[IO].of(List.empty[Int]).flatMap { tracker =>
      val sequential = new SequentialCompilerProcessors(
        Seq(notHandlingProcessor(1, tracker), trackingProcessor(2, tracker, success = true))
      )

      IO.pure(sequential.handles(testKey)).asserting(_ shouldBe true)
    }
  }

  it should "handle no key when none of its processors do" in {
    AtomicCell[IO].of(List.empty[Int]).flatMap { tracker =>
      val sequential = new SequentialCompilerProcessors(Seq(notHandlingProcessor(1, tracker)))

      IO.pure(sequential.handles(testKey)).asserting(_ shouldBe false)
    }
  }

  it should "handle empty processor list" in {
    val sequential = new SequentialCompilerProcessors(Seq.empty)

    runCompilerIO {
      sequential.generate(testKey)
    }.asserting(_ shouldBe Right(()))
  }

  private def trackingProcessor(
      id: Int,
      tracker: AtomicCell[IO, List[Int]],
      success: Boolean
  ): CompilerProcessor = (factKey: CompilerFactKey[?]) =>
    for {
      _ <- Kleisli.liftF(tracker.update(list => list :+ id))
      _ <- if (success) Monad[CompilerIO].unit
           else registerCompilerError(error(s"Processor $id failed"))
    } yield ()

  /** Tracks its invocation exactly as [[trackingProcessor]] does, but declares that it handles nothing — so a tracked
    * invocation means the pre-test was ignored.
    */
  private def notHandlingProcessor(id: Int, tracker: AtomicCell[IO, List[Int]]): CompilerProcessor =
    new CompilerProcessor {
      override def handles(factKey: CompilerFactKey[?]): Boolean = false

      override def generate(factKey: CompilerFactKey[?]): CompilerIO[Unit] =
        trackingProcessor(id, tracker, success = true).generate(factKey)
    }

  private def failingProcessor(errorMessage: String): CompilerProcessor = (factKey: CompilerFactKey[?]) =>
    registerCompilerError(error(errorMessage))

  private def successfulProcessor(): CompilerProcessor = (factKey: CompilerFactKey[?]) => Monad[CompilerIO].unit
}
