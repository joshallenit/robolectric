import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.compile.JavaCompile

class ShadowsPlugin implements Plugin<Project> {
    @Override
    void apply(Project project) {
        project.extensions.create("shadows", ShadowsPluginExtension)

        project.configurations {
            robolectricProcessor
        }

        project.dependencies {
            robolectricProcessor project.project(":robolectric-processor")
        }

        project.sourceSets.main.java.srcDirs += project.files("${project.buildDir}/generated-shadows")

        project.task("generateShadowProvider", type: JavaCompile, description: "Generate Shadows.shadowOf()s class") { task ->
            classpath = project.configurations.compile + project.configurations.robolectricProcessor
            source = project.sourceSets.main.java
            destinationDir = project.file("${project.buildDir}/generated-shadows")

            doFirst {
                options.compilerArgs.addAll(
                        "-proc:only",
                        "-processor", "org.robolectric.annotation.processing.RobolectricProcessor",
                        "-Aorg.robolectric.annotation.processing.shadowPackage=${project.shadows.packageName}"
                )
            }
        }


        def compileJavaTask = project.tasks["compileJava"]
        compileJavaTask.dependsOn("generateShadowProvider")
    }
}

class ShadowsPluginExtension {
    String packageName
}
