# Assistant AI

## Llama Thread Configuration

The number of threads used by the native Llama engine can be configured at build time using the `llmThreads` Gradle property:

```sh
./gradlew assemble -PllmThreads=4
```

If not specified, the build defaults to two threads.

### Recommended values

- **Android emulators:** 1–2 threads to keep host CPU usage reasonable.
- **Physical devices:** 2–4 threads for a balance between generation speed and power consumption.

The selected value is compiled into `BuildConfig.LLAMA_THREADS` and passed to `LlamaNative.llamaCreate`.
