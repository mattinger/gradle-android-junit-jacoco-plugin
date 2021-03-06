package com.vanniktech.android.junit.jacoco

import com.android.build.gradle.api.BaseVariant
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoMerge
import org.gradle.testing.jacoco.tasks.JacocoReport

class GenerationPlugin implements Plugin<Project> {
    @Override
    void apply(final Project rootProject) {
        rootProject.extensions.create('junitJacoco', JunitJacocoExtension)

        final def hasSubProjects = rootProject.subprojects.size() > 0

        if (hasSubProjects) {
            final def (JacocoMerge mergeTask, JacocoReport mergedReportTask) = addJacocoMergeToRootProject(rootProject, rootProject.junitJacoco)

            rootProject.subprojects { subProject ->
                afterEvaluate {
                    final def extension = rootProject.junitJacoco
                    addJacoco(subProject, extension, mergeTask, mergedReportTask)
                }
            }
        } else {
            rootProject.afterEvaluate {
                final def extension = rootProject.junitJacoco

                addJacoco(rootProject, extension)
            }
        }
    }

    protected static boolean addJacoco(final Project subProject, final JunitJacocoExtension extension) {
        return addJacoco(subProject, extension, null, null)
    }

    protected static boolean addJacoco(final Project subProject, final JunitJacocoExtension extension, JacocoMerge mergeTask, JacocoReport mergedReportTask) {
        if (!shouldIgnore(subProject, extension)) {
            if (isAndroidProject(subProject)) {
                return addJacocoAndroid(subProject, extension, mergeTask, mergedReportTask)
            } else if (isJavaProject(subProject)) {
                return addJacocoJava(subProject, extension, mergeTask, mergedReportTask)
            }
        }

        return false
    }

    private static boolean addJacocoJava(final Project subProject, final JunitJacocoExtension extension, JacocoMerge mergeTask, JacocoReport mergedReportTask) {
        subProject.plugins.apply('jacoco')

        subProject.jacoco {
            toolVersion extension.jacocoVersion
        }

        subProject.jacocoTestReport {
            dependsOn 'test'

            group = 'Reporting'
            description = 'Generate Jacoco coverage reports.'

            reports {
                xml.enabled = true
                csv.enabled = true
                html.enabled = true
            }

            getClassDirectories().from(subProject.fileTree(
                    dir: subProject.buildDir,
                    includes: ['**/classes/**/main/**'],
                    excludes: getExcludes(extension)
            ))

            final def coverageSourceDirs = [
                'src/main/clojure',
                'src/main/groovy',
                'src/main/java',
                'src/main/kotlin',
                'src/main/scala'
            ]

            getAdditionalSourceDirs().from(subProject.files(coverageSourceDirs))
            getSourceDirectories().from(subProject.files(coverageSourceDirs))
            getExecutionData().from(subProject.files(subProject.files("${subProject.buildDir}/jacoco/test.exec")))

            if (mergeTask != null) {
                mergeTask.executionData.setFrom(executionData.files + mergeTask.executionData.files)
            }
            if (mergedReportTask != null) {
                mergedReportTask.classDirectories.setFrom(classDirectories.getFrom() + mergedReportTask.classDirectories.getFrom())
                mergedReportTask.additionalSourceDirs.setFrom(additionalSourceDirs.getFrom() + mergedReportTask.additionalSourceDirs.getFrom())
                mergedReportTask.sourceDirectories.setFrom(sourceDirectories.getFrom() + mergedReportTask.sourceDirectories.getFrom())
            }
        }

        subProject.jacocoTestCoverageVerification {
            dependsOn 'jacocoTestReport'
            executionData(subProject.jacocoTestReport.executionData)
            sourceDirectories.setFrom(subProject.jacocoTestReport.sourceDirectories)
            classDirectories.setFrom(subProject.jacocoTestReport.classDirectories)
            additionalSourceDirs.setFrom(subProject.jacocoTestReport.additionalSourceDirs)
            additionalClassDirs.setFrom(subProject.jacocoTestReport.additionalClassDirs)
            violationRules {
              setFailOnViolation(extension.rules.failOnViolation)
              for (Rule r: extension.rules.rules) {
                rule {
                  setEnabled(r.enabled)
                  setElement(r.element)
                  setIncludes(r.includes)
                  setExcludes(r.excludes)
                  for (Limit l: r.limits) {
                    limit {
                      setCounter(l.counter)
                      setValue(l.value)
                      setMinimum(l.minimum)
                      setMaximum(l.maximum)
                    }
                  }
                }
              }
            }
        }

        subProject.check.dependsOn 'jacocoTestReport'
        return true
    }

    private static boolean addJacocoAndroid(final Project subProject, final JunitJacocoExtension extension, JacocoMerge mergeTask, JacocoReport mergedReportTask) {
        subProject.plugins.apply('jacoco')

        subProject.jacoco {
            toolVersion extension.jacocoVersion
        }

        subProject.tasks.withType(Test) {
            it.jacoco.includeNoLocationClasses = extension.includeNoLocationClasses
        }

        subProject.android.jacoco.version = extension.jacocoVersion

        Collection<BaseVariant> variants = []
        if (isAndroidApplication(subProject) || isAndroidDynamicFeature(subProject)) {
            variants = subProject.android.applicationVariants
        } else if (isAndroidLibrary(subProject) || isAndroidFeature(subProject)) {
            // FeatureExtension extends LibraryExtension
            variants = subProject.android.libraryVariants
        } else {
            // test plugin or something else
            return false
        }

        variants.all { variant ->
            def productFlavorName = variant.getFlavorName()
            def buildType = variant.getBuildType()
            def buildTypeName = buildType.name

            def sourceName, sourcePath
            if (!productFlavorName) {
                sourceName = sourcePath = "${buildTypeName}"
            } else {
                sourceName = "${productFlavorName}${buildTypeName.capitalize()}"
                sourcePath = "${productFlavorName}/${buildTypeName}"
            }

            final def jvmTaskName = "jacocoTestReport${sourceName.capitalize()}"
            final def combinedTaskName = "combinedTestReport${sourceName.capitalize()}"
            final def jvmCoverageTaskName = "jacocoTestCoverageVerification${sourceName.capitalize()}"

            final def jvmTestTaskName = "test${sourceName.capitalize()}UnitTest"
            final def instrumentationTestTaskName = "create${sourceName.capitalize()}CoverageReport"

            def reportTask = addJacocoTask(false, subProject, extension, mergeTask, mergedReportTask, jvmTaskName,
                jvmTestTaskName, instrumentationTestTaskName, sourceName, sourcePath, productFlavorName, buildTypeName)

            addCoverageVerifyTask(subProject, extension, reportTask, sourceName, jvmCoverageTaskName)

            if (buildType.testCoverageEnabled) {
                addJacocoTask(true, subProject, extension, mergeTask, mergedReportTask, combinedTaskName,
                    jvmTestTaskName, instrumentationTestTaskName, sourceName, sourcePath, productFlavorName, buildTypeName)
            }
        }

        return true
    }

    private static void addCoverageVerifyTask(final Project subProject,
                                              final JunitJacocoExtension extension,
                                              final JacocoReport reportTask,
                                              final String sourceName,
                                              final String coverageVerifyTaskName) {
      subProject.task(coverageVerifyTaskName, type: JacocoCoverageVerification) {
        group = 'Reporting'
        description = "Verify Jacoco coverage rules after running ${sourceName} tests."
        dependsOn(reportTask.name)
        executionData(reportTask.executionData)
        sourceDirectories.setFrom(reportTask.sourceDirectories)
        classDirectories.setFrom(reportTask.classDirectories)
        additionalSourceDirs.setFrom(reportTask.additionalSourceDirs)
        additionalClassDirs.setFrom(reportTask.additionalClassDirs)
        violationRules {
          setFailOnViolation(extension.rules.failOnViolation)
          for (Rule r: extension.rules.rules) {
            rule {
              setEnabled(r.enabled)
              setElement(r.element)
              setIncludes(r.includes)
              setExcludes(r.excludes)
              for (Limit l: r.limits) {
                limit {
                  setCounter(l.counter)
                  setValue(l.value)
                  setMinimum(l.minimum)
                  setMaximum(l.maximum)
                }
              }
            }
          }
        }
      }
    }

    private static JacocoReport addJacocoTask(final boolean combined, final Project subProject, final JunitJacocoExtension extension,
                                      JacocoMerge mergeTask, JacocoReport mergedReportTask, final String taskName,
                                      final String jvmTestTaskName, final String instrumentationTestTaskName, final String sourceName,
                                      final String sourcePath, final String productFlavorName, final String buildTypeName) {
        def destinationDir
        if (combined) {
            destinationDir = "${subProject.buildDir}/reports/jacocoCombined"
        } else {
            destinationDir = "${subProject.buildDir}/reports/jacoco"
        }

        def reportTask = subProject.task(taskName, type: JacocoReport) {
            group = 'Reporting'
            description = "Generate Jacoco coverage reports after running ${sourceName} tests."

            if (combined) {
                dependsOn jvmTestTaskName, instrumentationTestTaskName
            } else {
                dependsOn jvmTestTaskName
            }

            reports {
                xml {
                    enabled = true
                    destination subProject.file("$destinationDir/${sourceName}/jacoco.xml")
                }
                csv {
                    enabled = true
                    destination subProject.file("$destinationDir/${sourceName}/jacoco.csv")
                }
                html {
                    enabled = true
                    destination subProject.file("$destinationDir/${sourceName}")
                }
            }

            def classPaths = [
                "**/intermediates/classes/${sourcePath}/**",
                "**/intermediates/javac/${sourceName}/*/classes/**", // Android Gradle Plugin 3.2.x support.
                "**/intermediates/javac/${sourceName}/classes/**" // Android Gradle Plugin 3.4 and 3.5 support.
            ]

            if (isKotlinAndroid(subProject) || isKotlinMultiplatform(subProject)) {
                classPaths << "**/tmp/kotlin-classes/${sourcePath}/**"
                if (productFlavorName) {
                    classPaths << "**/tmp/kotlin-classes/${productFlavorName}${buildTypeName.capitalize()}/**"
                }
            }

            getClassDirectories().from(subProject.fileTree(
                dir: subProject.buildDir,
                includes: classPaths,
                excludes: getExcludes(extension)
            ))

            final def coverageSourceDirs = [
                "src/main/clojure",
                "src/main/groovy",
                "src/main/java",
                "src/main/kotlin",
                "src/main/scala",
                "src/$buildTypeName/clojure",
                "src/$buildTypeName/groovy",
                "src/$buildTypeName/java",
                "src/$buildTypeName/kotlin",
                "src/$buildTypeName/scala"
            ]

            if (productFlavorName) {
                coverageSourceDirs.add("src/$productFlavorName/clojure")
                coverageSourceDirs.add("src/$productFlavorName/groovy")
                coverageSourceDirs.add("src/$productFlavorName/java")
                coverageSourceDirs.add("src/$productFlavorName/kotlin")
                coverageSourceDirs.add("src/$productFlavorName/scala")
            }

            getAdditionalSourceDirs().from(subProject.files(coverageSourceDirs))
            getSourceDirectories().from(subProject.files(coverageSourceDirs))
            getExecutionData().from(subProject.files("${subProject.buildDir}/jacoco/${jvmTestTaskName}.exec"))

            if (combined) {
                // add instrumentation coverage execution data
                doFirst {
                    def instrumentationTestCoverageDirs = subProject.fileTree("${subProject.buildDir}/outputs/code_coverage")
                            .matching { include "**/*.ec" }
                    def allCodeCoverageFiles = instrumentationTestCoverageDirs.files + executionData.files
                    subProject.logger.with {
                        info("using following code coverage files for ${taskName}")
                        allCodeCoverageFiles.each { coverageFile ->
                            info(coverageFile.path)
                        }
                    }
                    executionData.setFrom(allCodeCoverageFiles)
                }
            }

            // add if true in extension or for the unit test Jacoco task
            def addToMergeTask = !combined || extension.includeInstrumentationCoverageInMergedReport

            if (mergeTask != null && addToMergeTask) {
                mergeTask.executionData.setFrom(executionData.files + mergeTask.executionData.files)
            }
            if (mergedReportTask != null && addToMergeTask) {
              mergedReportTask.classDirectories.setFrom(classDirectories.getFrom() + mergedReportTask.classDirectories.getFrom())
              mergedReportTask.additionalSourceDirs.setFrom(additionalSourceDirs.getFrom() + mergedReportTask.additionalSourceDirs.getFrom())
              mergedReportTask.sourceDirectories.setFrom(sourceDirectories.getFrom() + mergedReportTask.sourceDirectories.getFrom())
            }
        }

        subProject.check.dependsOn "${taskName}"

        return reportTask
    }

    protected static addJacocoMergeToRootProject(final Project project, final JunitJacocoExtension extension) {
        project.plugins.apply('jacoco')

        project.afterEvaluate {
            // Apply the Jacoco version after evaluating the project so that the extension could be configured
            project.jacoco {
                toolVersion extension.jacocoVersion
            }
        }

        def mergeTask = project.task("mergeJacocoReports", type: JacocoMerge) {
            executionData project.files().asFileTree // Start with an empty collection.
            destinationFile project.file("${project.buildDir}/jacoco/mergedReport.exec")

            doFirst {
                // Filter non existing files.
                def realExecutionData = project.files()

                executionData.each {
                    if (it.exists()) {
                        realExecutionData.setFrom(project.files(it) + realExecutionData.files)
                    }
                }

                executionData = realExecutionData
            }
        }

        def mergedReportTask = project.task("jacocoTestReportMerged", type: JacocoReport, dependsOn: mergeTask) {
            executionData mergeTask.destinationFile

            reports {
                xml {
                    enabled = true
                    destination project.file("${project.buildDir}/reports/jacoco/jacoco.xml")
                }
                csv {
                    enabled = true
                    destination project.file("${project.buildDir}/reports/jacoco/jacoco.csv")
                }
                html {
                    enabled = true
                    destination project.file("${project.buildDir}/reports/jacoco")
                }
            }

            // Start with empty collections.
            getClassDirectories().from(project.files())
            getAdditionalSourceDirs().from(project.files())
            getSourceDirectories().from(project.files())
        }

        return [mergeTask, mergedReportTask]
    }

    static List<String> getExcludes(final JunitJacocoExtension extension) {
        extension.excludes == null ? [
         '**/R.class',
         '**/R2.class', // ButterKnife Gradle Plugin.
         '**/R$*.class',
         '**/R2$*.class', // ButterKnife Gradle Plugin.
         '**/*$$*',
         '**/*$ViewInjector*.*', // Older ButterKnife Versions.
         '**/*$ViewBinder*.*', // Older ButterKnife Versions.
         '**/*_ViewBinding*.*', // Newer ButterKnife Versions.
         '**/BuildConfig.*',
         '**/Manifest*.*',
         '**/*$Lambda$*.*', // Jacoco can not handle several "$" in class name.
         '**/*Dagger*.*', // Dagger auto-generated code.
         '**/*MembersInjector*.*', // Dagger auto-generated code.
         '**/*_Provide*Factory*.*', // Dagger auto-generated code.
         '**/*_Factory*.*', // Dagger auto-generated code.
         '**/*$JsonObjectMapper.*', // LoganSquare auto-generated code.
         '**/*$inlined$*.*', // Kotlin specific, Jacoco can not handle several "$" in class name.
         '**/*$Icepick.*', // Icepick auto-generated code.
         '**/*$StateSaver.*', // android-state auto-generated code.
         '**/*AutoValue_*.*' // AutoValue auto-generated code.
        ] : extension.excludes
    }

    private static boolean isAndroidProject(final Project project) {
        final boolean isAndroidLibrary = project.plugins.hasPlugin('com.android.library')
        final boolean isAndroidApp = project.plugins.hasPlugin('com.android.application')
        final boolean isAndroidTest = project.plugins.hasPlugin('com.android.test')
        final boolean isAndroidFeature = project.plugins.hasPlugin('com.android.feature')
        final boolean isAndroidDynamicFeature = project.plugins.hasPlugin('com.android.dynamic-feature')
        final boolean isAndroidInstantApp = project.plugins.hasPlugin('com.android.instantapp')
        return isAndroidLibrary || isAndroidApp || isAndroidTest || isAndroidFeature || isAndroidDynamicFeature || isAndroidInstantApp
    }

    private static boolean isJavaProject(final Project project) {
        final boolean isJava = project.plugins.hasPlugin('java')
        final boolean isJavaLibrary = project.plugins.hasPlugin('java-library')
        final boolean isJavaGradlePlugin = project.plugins.hasPlugin('java-gradle-plugin')
        return isJava || isJavaLibrary || isJavaGradlePlugin
    }

    protected static boolean isKotlinAndroid(final Project project) {
        return project.plugins.hasPlugin('org.jetbrains.kotlin.android')
    }

    protected static boolean isKotlinMultiplatform(final Project project) {
        return project.plugins.hasPlugin('org.jetbrains.kotlin.multiplatform')
    }

    protected static boolean isAndroidApplication(final Project project) {
        return project.plugins.hasPlugin('com.android.application')
    }

    protected static boolean isAndroidLibrary(final Project project) {
        return project.plugins.hasPlugin('com.android.library')
    }

    protected static boolean isAndroidFeature(final Project project) {
        return project.plugins.hasPlugin('com.android.feature')
    }

    protected static boolean isAndroidDynamicFeature(final Project project) {
        return project.plugins.hasPlugin('com.android.dynamic-feature')
    }

    private static boolean shouldIgnore(final Project project, final JunitJacocoExtension extension) {
        if (extension.ignoreProjects?.contains(project.name) || extension.ignoreProjects?.contains(project.path)) {
            // Regex could be slower.
            return true
        }

        if (extension.ignoreProjects != null) {
            for (String ignoredProject : extension.ignoreProjects) {
                if (project.name.find(ignoredProject) || project.path.find(ignoredProject)) {
                    return true
                }
            }
        }

        return false
    }
}
