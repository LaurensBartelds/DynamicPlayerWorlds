plugins {
    id("gzmn.plugin-conventions")
}

description = "gzmn-worlds-proxy: the Velocity plugin, owns commands and placement"

base.archivesName.set("gzmn-worlds-proxy")

dependencies {
    compileOnly(libs.velocity.api)
    annotationProcessor(libs.velocity.api)
    implementation(project(":core"))
    implementation(libs.logstash.encoder)

    testImplementation(libs.velocity.api)
    testImplementation(project(":testing"))
}
