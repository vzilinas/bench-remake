load("@rules_java//java:defs.bzl", "java_binary")


package(default_visibility = ["//visibility:public"])

deps_bench = [
    "//contrib/spouts/kafka/src/java:heron-kafka-spout-java",
    "//heron/api/src/java:api-java-low-level",
    "//heron/common/src/java:basics-java",
    "//heron/simulator/src/java:simulator-java",
    "@maven//:org_apache_kafka_kafka_clients",
    "@maven//:org_slf4j_slf4j_api",
]

java_binary(
    name = "benchmark-unshaded",
    srcs = glob(["org/vz/benchmark/*.java"]),
    create_executable = 0,
    deps = deps_bench,
)

genrule(
    name = "benchmark",
    srcs = [":benchmark-unshaded_deploy.jar"],
    outs = ["benchmark.jar"],
    cmd = "cp $< $@",
)
