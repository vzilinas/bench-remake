bazel build benchmark/src/java:all
cp -p -f ./bazel-bin/benchmark/src/java/benchmark.jar compiled/benchmark-topology.jar