// 临时隔离 test-results 目录，避免并行 gradle 运行互相锁 binary 目录
allprojects {
    tasks.withType<Test>().configureEach {
        reports.junitXml.outputLocation.set(layout.buildDirectory.dir("test-results-mine/junitxml"))
        binaryResultsDirectory.set(layout.buildDirectory.dir("test-results-mine/binary"))
    }
}
