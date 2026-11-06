# libxposed loads this class by name from META-INF/xposed/java_init.list, so the
# whole hook package keeps its names: LSPosed instantiates the entry point and
# resolves the overridden callback methods reflectively.
-keep class io.github.leset0ng.hyperwave.module.** { *; }

# Provided by the framework at runtime, absent from the release classpath.
-dontwarn io.github.libxposed.api.**

# Keep the Compose UI entry point referenced from the manifest aliases.
-keep class io.github.leset0ng.hyperwave.MainActivity { *; }
-keep class io.github.leset0ng.hyperwave.AppearanceProvider { *; }
