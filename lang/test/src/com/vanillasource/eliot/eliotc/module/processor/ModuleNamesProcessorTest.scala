package com.vanillasource.eliot.eliotc.module.processor

import cats.effect.IO
import com.vanillasource.eliot.eliotc.ProcessorTest
import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, Qualifier}
import com.vanillasource.eliot.eliotc.ast.processor.ASTParser
import com.vanillasource.eliot.eliotc.core.processor.CoreProcessor
import com.vanillasource.eliot.eliotc.module.fact.ModuleNames
import com.vanillasource.eliot.eliotc.token.Tokenizer

class ModuleNamesProcessorTest
    extends ProcessorTest(Tokenizer(), ASTParser(), CoreProcessor(), ModuleNamesProcessor()) {

  "module names processor" should "extract a single name" in {
    runEngineForNames("def a: A").asserting(_ shouldBe Set(QualifiedName("a", Qualifier.Default)))
  }

  it should "extract multiple names" in {
    runEngineForNames("def a: A\ndef b: B").asserting(
      _ shouldBe Set(QualifiedName("a", Qualifier.Default), QualifiedName("b", Qualifier.Default))
    )
  }

  it should "extract names from data definitions" in {
    runEngineForNames("data Person").asserting { names =>
      names should contain(QualifiedName("Person", Qualifier.Type))
      names.exists(qn => qn.name == "TypeMatch" && qn.qualifier.isInstanceOf[Qualifier.AbilityImplementation]) shouldBe true
      names.exists(qn => qn.name == "Fields" && qn.qualifier.isInstanceOf[Qualifier.AbilityImplementation]) shouldBe true
      names.exists(qn => qn.name == "typeMatch" && qn.qualifier.isInstanceOf[Qualifier.AbilityImplementation]) shouldBe true
    }
  }

  it should "extract constructor and accessor names from data with fields" in {
    runEngineForNames("data Person(name: Name)").asserting { names =>
      names should contain(QualifiedName("Person", Qualifier.Type))
      names should contain(QualifiedName("Person", Qualifier.Default))
      names should contain(QualifiedName("name", Qualifier.Default))
      names.exists(qn => qn.name == "PatternMatch" && qn.qualifier.isInstanceOf[Qualifier.AbilityImplementation]) shouldBe true
      names.exists(qn => qn.name == "Cases" && qn.qualifier.isInstanceOf[Qualifier.AbilityImplementation]) shouldBe false
      names.exists(qn => qn.name == "handleCases" && qn.qualifier.isInstanceOf[Qualifier.AbilityImplementation]) shouldBe true
      names.exists(qn => qn.name == "TypeMatch" && qn.qualifier.isInstanceOf[Qualifier.AbilityImplementation]) shouldBe true
      names.exists(qn => qn.name == "Fields" && qn.qualifier.isInstanceOf[Qualifier.AbilityImplementation]) shouldBe true
      names.exists(qn => qn.name == "typeMatch" && qn.qualifier.isInstanceOf[Qualifier.AbilityImplementation]) shouldBe true
    }
  }

  it should "extract mixed function and data names" in {
    runEngineForNames("data A\ndef f: A").asserting { names =>
      names should contain(QualifiedName("A", Qualifier.Type))
      names should contain(QualifiedName("f", Qualifier.Default))
      names.exists(qn => qn.name == "typeMatch" && qn.qualifier.isInstanceOf[Qualifier.AbilityImplementation]) shouldBe true
    }
  }

  it should "detect duplicate names" in {
    runEngineForErrors("def a: A\ndef a: B").asserting(_ shouldBe Seq("Name was already defined in this module." at "a"))
  }

  it should "return empty set for empty module" in {
    runEngineForNames("").asserting(_ shouldBe Set())
  }

  private def runEngineForNames(source: String): IO[Set[QualifiedName]] =
    runGenerator(source, ModuleNames.Key(file)).map { case (_, facts) =>
      facts.values.collectFirst { case ModuleNames(_, names) => names.value.keySet }.getOrElse(Set.empty)
    }

  private def runEngineForErrors(source: String): IO[Seq[TestError]] =
    runGenerator(source, ModuleNames.Key(file)).map(result => toTestErrors(result._1))
}
