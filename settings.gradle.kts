rootProject.name = "myos-core-better"
includeBuild(providers.gradleProperty("blueLibrary").getOrElse("../blue-java-better"))
