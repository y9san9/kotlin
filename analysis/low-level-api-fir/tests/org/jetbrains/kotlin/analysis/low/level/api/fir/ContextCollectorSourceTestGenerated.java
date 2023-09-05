/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.low.level.api.fir;

import com.intellij.testFramework.TestDataPath;
import org.jetbrains.kotlin.test.util.KtTestUtil;
import org.jetbrains.kotlin.test.TestMetadata;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.regex.Pattern;

/** This class is generated by {@link org.jetbrains.kotlin.generators.tests.analysis.api.GenerateAnalysisApiTestsKt}. DO NOT MODIFY MANUALLY */
@SuppressWarnings("all")
@TestMetadata("analysis/low-level-api-fir/testdata/contextCollector")
@TestDataPath("$PROJECT_ROOT")
public class ContextCollectorSourceTestGenerated extends AbstractContextCollectorSourceTest {
    @Test
    public void testAllFilesPresentInContextCollector() throws Exception {
        KtTestUtil.assertAllTestsPresentByMetadataWithExcluded(this.getClass(), new File("analysis/low-level-api-fir/testdata/contextCollector"), Pattern.compile("^(.+)\\.(kt)$"), null, true);
    }

    @Test
    @TestMetadata("contextReceivers.kt")
    public void testContextReceivers() throws Exception {
        runTest("analysis/low-level-api-fir/testdata/contextCollector/contextReceivers.kt");
    }

    @Test
    @TestMetadata("contextReceiversClass.kt")
    public void testContextReceiversClass() throws Exception {
        runTest("analysis/low-level-api-fir/testdata/contextCollector/contextReceiversClass.kt");
    }

    @Test
    @TestMetadata("enumValue.kt")
    public void testEnumValue() throws Exception {
        runTest("analysis/low-level-api-fir/testdata/contextCollector/enumValue.kt");
    }

    @Test
    @TestMetadata("extensionFunction.kt")
    public void testExtensionFunction() throws Exception {
        runTest("analysis/low-level-api-fir/testdata/contextCollector/extensionFunction.kt");
    }

    @Test
    @TestMetadata("extensionLambdas.kt")
    public void testExtensionLambdas() throws Exception {
        runTest("analysis/low-level-api-fir/testdata/contextCollector/extensionLambdas.kt");
    }

    @Test
    @TestMetadata("file.kt")
    public void testFile() throws Exception {
        runTest("analysis/low-level-api-fir/testdata/contextCollector/file.kt");
    }

    @Test
    @TestMetadata("innerClasses.kt")
    public void testInnerClasses() throws Exception {
        runTest("analysis/low-level-api-fir/testdata/contextCollector/innerClasses.kt");
    }

    @Test
    @TestMetadata("lambdaArguments.kt")
    public void testLambdaArguments() throws Exception {
        runTest("analysis/low-level-api-fir/testdata/contextCollector/lambdaArguments.kt");
    }

    @Test
    @TestMetadata("localClass.kt")
    public void testLocalClass() throws Exception {
        runTest("analysis/low-level-api-fir/testdata/contextCollector/localClass.kt");
    }

    @Test
    @TestMetadata("nestedClasses.kt")
    public void testNestedClasses() throws Exception {
        runTest("analysis/low-level-api-fir/testdata/contextCollector/nestedClasses.kt");
    }

    @Test
    @TestMetadata("primaryConstructorParameter.kt")
    public void testPrimaryConstructorParameter() throws Exception {
        runTest("analysis/low-level-api-fir/testdata/contextCollector/primaryConstructorParameter.kt");
    }

    @Test
    @TestMetadata("propertyDelegateInitializer.kt")
    public void testPropertyDelegateInitializer() throws Exception {
        runTest("analysis/low-level-api-fir/testdata/contextCollector/propertyDelegateInitializer.kt");
    }

    @Test
    @TestMetadata("simple.kt")
    public void testSimple() throws Exception {
        runTest("analysis/low-level-api-fir/testdata/contextCollector/simple.kt");
    }

    @Nested
    @TestMetadata("analysis/low-level-api-fir/testdata/contextCollector/classHeaderPositions")
    @TestDataPath("$PROJECT_ROOT")
    public class ClassHeaderPositions {
        @Test
        public void testAllFilesPresentInClassHeaderPositions() throws Exception {
            KtTestUtil.assertAllTestsPresentByMetadataWithExcluded(this.getClass(), new File("analysis/low-level-api-fir/testdata/contextCollector/classHeaderPositions"), Pattern.compile("^(.+)\\.(kt)$"), null, true);
        }

        @Test
        @TestMetadata("contextReceiver.kt")
        public void testContextReceiver() throws Exception {
            runTest("analysis/low-level-api-fir/testdata/contextCollector/classHeaderPositions/contextReceiver.kt");
        }

        @Test
        @TestMetadata("primaryConstructorParameter_initializerExpression.kt")
        public void testPrimaryConstructorParameter_initializerExpression() throws Exception {
            runTest("analysis/low-level-api-fir/testdata/contextCollector/classHeaderPositions/primaryConstructorParameter_initializerExpression.kt");
        }

        @Test
        @TestMetadata("primaryConstructorParameter_typeRef.kt")
        public void testPrimaryConstructorParameter_typeRef() throws Exception {
            runTest("analysis/low-level-api-fir/testdata/contextCollector/classHeaderPositions/primaryConstructorParameter_typeRef.kt");
        }

        @Test
        @TestMetadata("superTypeCallArgumentsExpression.kt")
        public void testSuperTypeCallArgumentsExpression() throws Exception {
            runTest("analysis/low-level-api-fir/testdata/contextCollector/classHeaderPositions/superTypeCallArgumentsExpression.kt");
        }

        @Test
        @TestMetadata("superTypeCallArgumentsTypeRef.kt")
        public void testSuperTypeCallArgumentsTypeRef() throws Exception {
            runTest("analysis/low-level-api-fir/testdata/contextCollector/classHeaderPositions/superTypeCallArgumentsTypeRef.kt");
        }

        @Test
        @TestMetadata("superTypeCallee.kt")
        public void testSuperTypeCallee() throws Exception {
            runTest("analysis/low-level-api-fir/testdata/contextCollector/classHeaderPositions/superTypeCallee.kt");
        }

        @Test
        @TestMetadata("superTypeCalleeGenerics.kt")
        public void testSuperTypeCalleeGenerics() throws Exception {
            runTest("analysis/low-level-api-fir/testdata/contextCollector/classHeaderPositions/superTypeCalleeGenerics.kt");
        }

        @Test
        @TestMetadata("superTypeDelegatedExpression.kt")
        public void testSuperTypeDelegatedExpression() throws Exception {
            runTest("analysis/low-level-api-fir/testdata/contextCollector/classHeaderPositions/superTypeDelegatedExpression.kt");
        }

        @Test
        @TestMetadata("superTypeDelegatedTypeRef.kt")
        public void testSuperTypeDelegatedTypeRef() throws Exception {
            runTest("analysis/low-level-api-fir/testdata/contextCollector/classHeaderPositions/superTypeDelegatedTypeRef.kt");
        }

        @Test
        @TestMetadata("superTypeRef.kt")
        public void testSuperTypeRef() throws Exception {
            runTest("analysis/low-level-api-fir/testdata/contextCollector/classHeaderPositions/superTypeRef.kt");
        }

        @Test
        @TestMetadata("superTypeRefGenerics.kt")
        public void testSuperTypeRefGenerics() throws Exception {
            runTest("analysis/low-level-api-fir/testdata/contextCollector/classHeaderPositions/superTypeRefGenerics.kt");
        }
    }

    @Nested
    @TestMetadata("analysis/low-level-api-fir/testdata/contextCollector/scripts")
    @TestDataPath("$PROJECT_ROOT")
    public class Scripts {
        @Test
        public void testAllFilesPresentInScripts() throws Exception {
            KtTestUtil.assertAllTestsPresentByMetadataWithExcluded(this.getClass(), new File("analysis/low-level-api-fir/testdata/contextCollector/scripts"), Pattern.compile("^(.+)\\.(kt)$"), null, true);
        }
    }

    @Nested
    @TestMetadata("analysis/low-level-api-fir/testdata/contextCollector/smartCasts")
    @TestDataPath("$PROJECT_ROOT")
    public class SmartCasts {
        @Test
        @TestMetadata("afterIf.kt")
        public void testAfterIf() throws Exception {
            runTest("analysis/low-level-api-fir/testdata/contextCollector/smartCasts/afterIf.kt");
        }

        @Test
        @TestMetadata("afterLoop.kt")
        public void testAfterLoop() throws Exception {
            runTest("analysis/low-level-api-fir/testdata/contextCollector/smartCasts/afterLoop.kt");
        }

        @Test
        public void testAllFilesPresentInSmartCasts() throws Exception {
            KtTestUtil.assertAllTestsPresentByMetadataWithExcluded(this.getClass(), new File("analysis/low-level-api-fir/testdata/contextCollector/smartCasts"), Pattern.compile("^(.+)\\.(kt)$"), null, true);
        }

        @Test
        @TestMetadata("andRight.kt")
        public void testAndRight() throws Exception {
            runTest("analysis/low-level-api-fir/testdata/contextCollector/smartCasts/andRight.kt");
        }

        @Test
        @TestMetadata("argument.kt")
        public void testArgument() throws Exception {
            runTest("analysis/low-level-api-fir/testdata/contextCollector/smartCasts/argument.kt");
        }

        @Test
        @TestMetadata("argumentAsReceiver.kt")
        public void testArgumentAsReceiver() throws Exception {
            runTest("analysis/low-level-api-fir/testdata/contextCollector/smartCasts/argumentAsReceiver.kt");
        }

        @Test
        @TestMetadata("beforeIf.kt")
        public void testBeforeIf() throws Exception {
            runTest("analysis/low-level-api-fir/testdata/contextCollector/smartCasts/beforeIf.kt");
        }

        @Test
        @TestMetadata("beforeLoop.kt")
        public void testBeforeLoop() throws Exception {
            runTest("analysis/low-level-api-fir/testdata/contextCollector/smartCasts/beforeLoop.kt");
        }

        @Test
        @TestMetadata("dispatchReceiver.kt")
        public void testDispatchReceiver() throws Exception {
            runTest("analysis/low-level-api-fir/testdata/contextCollector/smartCasts/dispatchReceiver.kt");
        }

        @Test
        @TestMetadata("extensionReceiver.kt")
        public void testExtensionReceiver() throws Exception {
            runTest("analysis/low-level-api-fir/testdata/contextCollector/smartCasts/extensionReceiver.kt");
        }

        @Test
        @TestMetadata("insideLoop.kt")
        public void testInsideLoop() throws Exception {
            runTest("analysis/low-level-api-fir/testdata/contextCollector/smartCasts/insideLoop.kt");
        }

        @Test
        @TestMetadata("orRight.kt")
        public void testOrRight() throws Exception {
            runTest("analysis/low-level-api-fir/testdata/contextCollector/smartCasts/orRight.kt");
        }

        @Test
        @TestMetadata("plainCheck.kt")
        public void testPlainCheck() throws Exception {
            runTest("analysis/low-level-api-fir/testdata/contextCollector/smartCasts/plainCheck.kt");
        }

        @Test
        @TestMetadata("require.kt")
        public void testRequire() throws Exception {
            runTest("analysis/low-level-api-fir/testdata/contextCollector/smartCasts/require.kt");
        }

        @Test
        @TestMetadata("when.kt")
        public void testWhen() throws Exception {
            runTest("analysis/low-level-api-fir/testdata/contextCollector/smartCasts/when.kt");
        }
    }
}
