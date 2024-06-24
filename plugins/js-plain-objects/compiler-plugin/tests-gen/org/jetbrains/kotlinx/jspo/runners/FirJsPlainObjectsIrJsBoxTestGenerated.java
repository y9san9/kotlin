/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlinx.jspo.runners;

import com.intellij.testFramework.TestDataPath;
import org.jetbrains.kotlin.test.util.KtTestUtil;
import org.jetbrains.kotlin.test.TargetBackend;
import org.jetbrains.kotlin.test.TestMetadata;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.regex.Pattern;

/** This class is generated by {@link org.jetbrains.kotlinx.jspo.TestGeneratorKt}. DO NOT MODIFY MANUALLY */
@SuppressWarnings("all")
@TestMetadata("plugins/js-plain-objects/compiler-plugin/testData/box")
@TestDataPath("$PROJECT_ROOT")
public class FirJsPlainObjectsIrJsBoxTestGenerated extends AbstractFirJsPlainObjectsIrJsBoxTest {
  @Test
  public void testAllFilesPresentInBox() {
    KtTestUtil.assertAllTestsPresentByMetadataWithExcluded(this.getClass(), new File("plugins/js-plain-objects/compiler-plugin/testData/box"), Pattern.compile("^(.+)\\.kt$"), null, TargetBackend.JS_IR, true);
  }

  @Test
  @TestMetadata("copy.kt")
  public void testCopy() {
    runTest("plugins/js-plain-objects/compiler-plugin/testData/box/copy.kt");
  }

  @Test
  @TestMetadata("multiple-module.kt")
  public void testMultiple_module() {
    runTest("plugins/js-plain-objects/compiler-plugin/testData/box/multiple-module.kt");
  }

  @Test
  @TestMetadata("optional.kt")
  public void testOptional() {
    runTest("plugins/js-plain-objects/compiler-plugin/testData/box/optional.kt");
  }

  @Test
  @TestMetadata("optionalByTypealias.kt")
  public void testOptionalByTypealias() {
    runTest("plugins/js-plain-objects/compiler-plugin/testData/box/optionalByTypealias.kt");
  }

  @Test
  @TestMetadata("reservedKeywordsAsPropery.kt")
  public void testReservedKeywordsAsPropery() {
    runTest("plugins/js-plain-objects/compiler-plugin/testData/box/reservedKeywordsAsPropery.kt");
  }

  @Test
  @TestMetadata("simple.kt")
  public void testSimple() {
    runTest("plugins/js-plain-objects/compiler-plugin/testData/box/simple.kt");
  }

  @Test
  @TestMetadata("simpleWithTypeParameter.kt")
  public void testSimpleWithTypeParameter() {
    runTest("plugins/js-plain-objects/compiler-plugin/testData/box/simpleWithTypeParameter.kt");
  }

  @Test
  @TestMetadata("with-inheritance.kt")
  public void testWith_inheritance() {
    runTest("plugins/js-plain-objects/compiler-plugin/testData/box/with-inheritance.kt");
  }

  @Test
  @TestMetadata("with-multiple-inheritance.kt")
  public void testWith_multiple_inheritance() {
    runTest("plugins/js-plain-objects/compiler-plugin/testData/box/with-multiple-inheritance.kt");
  }
}
