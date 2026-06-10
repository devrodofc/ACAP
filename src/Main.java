import org.jocl.CL;
import org.jocl.CLException;
import org.jocl.Pointer;
import org.jocl.Sizeof;
import org.jocl.cl_command_queue;
import org.jocl.cl_context;
import org.jocl.cl_context_properties;
import org.jocl.cl_device_id;
import org.jocl.cl_mem;
import org.jocl.cl_platform_id;
import org.jocl.cl_program;
import org.jocl.cl_kernel;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.jocl.CL.CL_CONTEXT_PLATFORM;
import static org.jocl.CL.CL_DEVICE_NAME;
import static org.jocl.CL.CL_DEVICE_TYPE_ALL;
import static org.jocl.CL.CL_DEVICE_TYPE_GPU;
import static org.jocl.CL.CL_MEM_COPY_HOST_PTR;
import static org.jocl.CL.CL_MEM_READ_ONLY;
import static org.jocl.CL.CL_MEM_WRITE_ONLY;
import static org.jocl.CL.CL_PLATFORM_NAME;
import static org.jocl.CL.CL_TRUE;
import static org.jocl.CL.clBuildProgram;
import static org.jocl.CL.clCreateBuffer;
import static org.jocl.CL.clCreateCommandQueueWithProperties;
import static org.jocl.CL.clCreateContext;
import static org.jocl.CL.clCreateKernel;
import static org.jocl.CL.clCreateProgramWithSource;
import static org.jocl.CL.clEnqueueNDRangeKernel;
import static org.jocl.CL.clEnqueueReadBuffer;
import static org.jocl.CL.clFinish;
import static org.jocl.CL.clGetDeviceIDs;
import static org.jocl.CL.clGetDeviceInfo;
import static org.jocl.CL.clGetPlatformIDs;
import static org.jocl.CL.clGetPlatformInfo;
import static org.jocl.CL.clReleaseCommandQueue;
import static org.jocl.CL.clReleaseContext;
import static org.jocl.CL.clReleaseKernel;
import static org.jocl.CL.clReleaseMemObject;
import static org.jocl.CL.clReleaseProgram;
import static org.jocl.CL.clSetKernelArg;

public class Main {
    private static final Path DEFAULT_SAMPLE_DIR = Paths.get(System.getProperty("user.home"), "Downloads", "Amostras");
    private static final Path DEFAULT_OUTPUT_CSV = Paths.get("results", "resultados.csv");
    private static final Path DEFAULT_CHART_DIR = Paths.get("results", "graficos");
    private static final String DEFAULT_WORD = "the";
    private static final int DEFAULT_RUNS = 3;

    public static void main(String[] args) throws Exception {
        Config config = Config.parse(args);
        if (config.help) {
            printHelp();
            return;
        }

        if (config.inputFile != null) {
            runSingle(config);
            return;
        }

        runBenchmark(config);
    }

    public static CountResult serialCPU(byte[] text, byte[] word) {
        long start = System.nanoTime();
        long occurrences = countRange(text, word, 0, text.length);
        return CountResult.ok("SerialCPU", "1", occurrences, elapsedMillis(start), "CPU serial");
    }

    public static CountResult parallelCPU(byte[] text, byte[] word, int threads) throws InterruptedException, ExecutionException {
        int workers = Math.max(1, threads);
        long start = System.nanoTime();
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        try {
            int chunkSize = Math.max(1, (text.length + workers - 1) / workers);
            List<Future<Long>> futures = new ArrayList<>();
            for (int worker = 0; worker < workers; worker++) {
                int rangeStart = worker * chunkSize;
                int rangeEnd = Math.min(text.length, rangeStart + chunkSize);
                if (rangeStart >= rangeEnd) {
                    break;
                }
                futures.add(executor.submit(new CountTask(text, word, rangeStart, rangeEnd)));
            }

            long occurrences = 0;
            for (Future<Long> future : futures) {
                occurrences += future.get();
            }
            return CountResult.ok("ParallelCPU", Integer.toString(workers), occurrences, elapsedMillis(start), "ExecutorService");
        } finally {
            executor.shutdownNow();
        }
    }

    public static CountResult parallelGPU(OpenClCounter counter, byte[] text, byte[] word) {
        if (counter == null) {
            return CountResult.skipped("ParallelGPU", "OpenCL indisponivel", "SKIPPED: nenhum dispositivo OpenCL encontrado");
        }

        long start = System.nanoTime();
        try {
            long occurrences = counter.count(text, word);
            return CountResult.ok("ParallelGPU", "OpenCL", occurrences, elapsedMillis(start), counter.deviceLabel());
        } catch (RuntimeException ex) {
            return CountResult.skipped("ParallelGPU", counter.deviceLabel(), "ERROR: " + ex.getMessage());
        }
    }

    private static void runSingle(Config config) throws Exception {
        byte[] text = readNormalizedAscii(config.inputFile);
        byte[] word = normalizeWord(config.word);
        if (word.length == 0) {
            throw new IllegalArgumentException("A palavra de busca nao pode ser vazia.");
        }

        int cpuThreads = config.threadCounts.isEmpty()
                ? Runtime.getRuntime().availableProcessors()
                : config.threadCounts.get(config.threadCounts.size() - 1);

        try (OpenClCounter gpu = config.gpuEnabled ? OpenClCounter.create().orElse(null) : null) {
            CountResult serial = serialCPU(text, word);
            CountResult cpu = parallelCPU(text, word, cpuThreads);
            CountResult opencl = config.gpuEnabled
                    ? parallelGPU(gpu, text, word)
                    : CountResult.skipped("ParallelGPU", "desativado", "SKIPPED: execucao GPU desativada");

            printExampleLine(serial);
            printExampleLine(cpu);
            printExampleLine(opencl);
        }
    }

    private static void runBenchmark(Config config) throws Exception {
        List<Path> samples = listSamples(config.sampleDir);
        if (samples.isEmpty()) {
            throw new IllegalArgumentException("Nenhum arquivo .txt encontrado em " + config.sampleDir.toAbsolutePath());
        }

        byte[] word = normalizeWord(config.word);
        if (word.length == 0) {
            throw new IllegalArgumentException("A palavra de busca nao pode ser vazia.");
        }

        Files.createDirectories(config.outputCsv.getParent());
        Files.createDirectories(config.chartDir);

        List<ResultRow> rows = new ArrayList<>();
        OpenClCounter gpu = null;
        try {
            if (config.gpuEnabled) {
                gpu = OpenClCounter.create().orElse(null);
                if (gpu == null) {
                    System.out.println("ParallelGPU: nenhum dispositivo OpenCL encontrado; as linhas GPU serao marcadas como SKIPPED.");
                } else {
                    System.out.println("ParallelGPU: usando " + gpu.deviceLabel());
                }
            }

            for (Path sample : samples) {
                byte[] text = readNormalizedAscii(sample);
                long size = Files.size(sample);
                System.out.println("\nArquivo: " + sample.getFileName() + " | " + size + " bytes | palavra: " + config.word);
                warmUp(config, gpu, text, word);

                for (int run = 1; run <= config.runs; run++) {
                    CountResult serial = serialCPU(text, word);
                    rows.add(ResultRow.from(sample, size, config.word, run, serial));
                    printRunLine(run, serial);

                    for (int threads : config.threadCounts) {
                        CountResult cpu = parallelCPU(text, word, threads);
                        rows.add(ResultRow.from(sample, size, config.word, run, cpu));
                        printRunLine(run, cpu);
                    }

                    CountResult opencl = config.gpuEnabled
                            ? parallelGPU(gpu, text, word)
                            : CountResult.skipped("ParallelGPU", "desativado", "SKIPPED: execucao GPU desativada");
                    rows.add(ResultRow.from(sample, size, config.word, run, opencl));
                    printRunLine(run, opencl);
                }
            }
        } finally {
            if (gpu != null) {
                gpu.close();
            }
        }

        writeCsv(config.outputCsv, rows);
        ChartGenerator.writeCharts(config.chartDir, rows);

        System.out.println("\nCSV gerado em: " + config.outputCsv.toAbsolutePath());
        System.out.println("Graficos SVG gerados em: " + config.chartDir.toAbsolutePath());
    }

    private static void warmUp(Config config, OpenClCounter gpu, byte[] text, byte[] word) throws Exception {
        serialCPU(text, word);
        if (!config.threadCounts.isEmpty()) {
            parallelCPU(text, word, config.threadCounts.get(config.threadCounts.size() - 1));
        }
        if (gpu != null) {
            parallelGPU(gpu, text, word);
        }
    }

    private static void printHelp() {
        System.out.println("Uso:");
        System.out.println("  java -cp out:$JOCL_JAR Main [opcoes]");
        System.out.println();
        System.out.println("Benchmark completo:");
        System.out.println("  --samples <dir>       Diretorio com arquivos .txt (padrao: ~/Downloads/Amostras)");
        System.out.println("  --word <palavra>      Palavra a contar (padrao: the)");
        System.out.println("  --runs <n>            Repeticoes por metodo/amostra (padrao: 3)");
        System.out.println("  --threads <lista>     Threads do ParallelCPU, exemplo: 1,2,4,8");
        System.out.println("  --output <arquivo>    CSV de saida (padrao: results/resultados.csv)");
        System.out.println("  --charts <dir>        Diretorio dos graficos SVG (padrao: results/graficos)");
        System.out.println("  --no-gpu              Nao executa OpenCL");
        System.out.println();
        System.out.println("Execucao simples:");
        System.out.println("  --input <arquivo.txt> --word <palavra> [--threads 4]");
    }

    private static List<Path> listSamples(Path sampleDir) throws IOException {
        if (!Files.isDirectory(sampleDir)) {
            throw new IllegalArgumentException("Diretorio de amostras nao encontrado: " + sampleDir.toAbsolutePath());
        }
        Collator collator = Collator.getInstance(Locale.ROOT);
        try (Stream<Path> files = Files.list(sampleDir)) {
            return files
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".txt"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString(), collator))
                    .collect(Collectors.toList());
        }
    }

    private static byte[] readNormalizedAscii(Path file) throws IOException {
        byte[] data = Files.readAllBytes(file);
        for (int i = 0; i < data.length; i++) {
            int value = data[i] & 0xFF;
            if (value >= 'A' && value <= 'Z') {
                data[i] = (byte) (value + ('a' - 'A'));
            }
        }
        return data;
    }

    private static byte[] normalizeWord(String word) {
        String normalized = Objects.requireNonNull(word, "word")
                .strip()
                .toLowerCase(Locale.ROOT);
        return normalized.getBytes(StandardCharsets.UTF_8);
    }

    private static long countRange(byte[] text, byte[] word, int startInclusive, int endExclusive) {
        if (word.length == 0 || text.length == 0 || word.length > text.length) {
            return 0;
        }

        long count = 0;
        int safeEnd = Math.min(endExclusive, text.length);
        for (int i = Math.max(0, startInclusive); i < safeEnd; i++) {
            if (matchesWholeWordAt(text, word, i)) {
                count++;
            }
        }
        return count;
    }

    private static boolean matchesWholeWordAt(byte[] text, byte[] word, int position) {
        int after = position + word.length;
        if (after > text.length) {
            return false;
        }
        if (position > 0 && isWordByte(text[position - 1])) {
            return false;
        }
        if (after < text.length && isWordByte(text[after])) {
            return false;
        }
        for (int i = 0; i < word.length; i++) {
            if (text[position + i] != word[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean isWordByte(byte value) {
        int c = value & 0xFF;
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }

    private static double elapsedMillis(long startNano) {
        return (System.nanoTime() - startNano) / 1_000_000.0;
    }

    private static void printExampleLine(CountResult result) {
        if (result.ok) {
            System.out.printf(Locale.US, "%s: %d ocorrencias em %.3f ms%n", result.method, result.occurrences, result.timeMillis);
        } else {
            System.out.printf("%s: %s%n", result.method, result.status);
        }
    }

    private static void printRunLine(int run, CountResult result) {
        String workerLabel = result.workers == null || result.workers.isBlank() ? "" : " [" + result.workers + "]";
        if (result.ok) {
            System.out.printf(Locale.US, "  execucao %d - %s%s: %d ocorrencias em %.3f ms%n",
                    run, result.method, workerLabel, result.occurrences, result.timeMillis);
        } else {
            System.out.printf("  execucao %d - %s%s: %s%n", run, result.method, workerLabel, result.status);
        }
    }

    private static void writeCsv(Path outputCsv, List<ResultRow> rows) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(outputCsv, StandardCharsets.UTF_8)) {
            writer.write("sample,file_size_bytes,word,method,workers,run,occurrences,time_ms,device,status");
            writer.newLine();
            for (ResultRow row : rows) {
                writer.write(csv(row.sample()));
                writer.write(',');
                writer.write(Long.toString(row.fileSizeBytes()));
                writer.write(',');
                writer.write(csv(row.word()));
                writer.write(',');
                writer.write(csv(row.method()));
                writer.write(',');
                writer.write(csv(row.workers()));
                writer.write(',');
                writer.write(Integer.toString(row.run()));
                writer.write(',');
                writer.write(Long.toString(row.occurrences()));
                writer.write(',');
                writer.write(String.format(Locale.US, "%.6f", row.timeMillis()));
                writer.write(',');
                writer.write(csv(row.device()));
                writer.write(',');
                writer.write(csv(row.status()));
                writer.newLine();
            }
        }
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        String escaped = value.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }

    private record CountTask(byte[] text, byte[] word, int start, int end) implements Callable<Long> {
        @Override
        public Long call() {
            return countRange(text, word, start, end);
        }
    }

    public record CountResult(String method, String workers, long occurrences, double timeMillis,
                              String device, boolean ok, String status) {
        static CountResult ok(String method, String workers, long occurrences, double timeMillis, String device) {
            return new CountResult(method, workers, occurrences, timeMillis, device, true, "OK");
        }

        static CountResult skipped(String method, String device, String status) {
            return new CountResult(method, "", 0, 0.0, device, false, status);
        }
    }

    public record ResultRow(String sample, long fileSizeBytes, String word, String method, String workers,
                            int run, long occurrences, double timeMillis, String device, String status) {
        static ResultRow from(Path sample, long fileSizeBytes, String word, int run, CountResult result) {
            return new ResultRow(
                    sample.getFileName().toString(),
                    fileSizeBytes,
                    word,
                    result.method(),
                    result.workers(),
                    run,
                    result.occurrences(),
                    result.timeMillis(),
                    result.device(),
                    result.status()
            );
        }
    }

    private static final class Config {
        Path sampleDir = DEFAULT_SAMPLE_DIR;
        Path inputFile;
        Path outputCsv = DEFAULT_OUTPUT_CSV;
        Path chartDir = DEFAULT_CHART_DIR;
        String word = DEFAULT_WORD;
        int runs = DEFAULT_RUNS;
        List<Integer> threadCounts = defaultThreadCounts();
        boolean gpuEnabled = true;
        boolean help = false;

        static Config parse(String[] args) {
            Config config = new Config();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--help", "-h" -> config.help = true;
                    case "--samples" -> config.sampleDir = Paths.get(requireValue(args, ++i, arg));
                    case "--input" -> config.inputFile = Paths.get(requireValue(args, ++i, arg));
                    case "--word" -> config.word = requireValue(args, ++i, arg);
                    case "--runs" -> config.runs = Math.max(1, Integer.parseInt(requireValue(args, ++i, arg)));
                    case "--threads" -> config.threadCounts = parseThreadCounts(requireValue(args, ++i, arg));
                    case "--output" -> config.outputCsv = Paths.get(requireValue(args, ++i, arg));
                    case "--charts" -> config.chartDir = Paths.get(requireValue(args, ++i, arg));
                    case "--no-gpu" -> config.gpuEnabled = false;
                    default -> throw new IllegalArgumentException("Opcao desconhecida: " + arg + ". Use --help.");
                }
            }
            if (config.outputCsv.getParent() == null) {
                config.outputCsv = Paths.get(".").resolve(config.outputCsv);
            }
            return config;
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length || args[index].startsWith("--")) {
                throw new IllegalArgumentException("A opcao " + option + " exige um valor.");
            }
            return args[index];
        }

        private static List<Integer> parseThreadCounts(String raw) {
            Set<Integer> values = new LinkedHashSet<>();
            for (String part : raw.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    values.add(Math.max(1, Integer.parseInt(trimmed)));
                }
            }
            if (values.isEmpty()) {
                throw new IllegalArgumentException("Informe pelo menos um valor em --threads.");
            }
            return new ArrayList<>(values);
        }

        private static List<Integer> defaultThreadCounts() {
            int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
            Set<Integer> values = new LinkedHashSet<>();
            values.add(1);
            values.add(Math.min(2, processors));
            values.add(Math.min(4, processors));
            values.add(processors);
            return values.stream().filter(value -> value >= 1).collect(Collectors.toList());
        }
    }

    public static final class OpenClCounter implements AutoCloseable {
        private static final String KERNEL_SOURCE = """
                inline int is_word_char(uchar c) {
                    return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
                }

                __kernel void count_word(__global const uchar *text,
                                         const int text_length,
                                         __global const uchar *word,
                                         const int word_length,
                                         __global int *partial) {
                    int gid = get_global_id(0);
                    if (gid >= text_length) {
                        return;
                    }

                    int found = 0;
                    if (word_length > 0 && gid + word_length <= text_length) {
                        uchar previous = gid == 0 ? (uchar)' ' : text[gid - 1];
                        if (!is_word_char(previous)) {
                            int matches = 1;
                            for (int i = 0; i < word_length; i++) {
                                if (text[gid + i] != word[i]) {
                                    matches = 0;
                                    break;
                                }
                            }

                            int after = gid + word_length;
                            uchar next = after >= text_length ? (uchar)' ' : text[after];
                            if (matches && !is_word_char(next)) {
                                found = 1;
                            }
                        }
                    }
                    partial[gid] = found;
                }
                """;

        private final cl_platform_id platform;
        private final cl_device_id device;
        private final cl_context context;
        private final cl_command_queue queue;
        private final cl_program program;
        private final cl_kernel kernel;
        private final String platformName;
        private final String deviceName;
        private final boolean selectedGpu;

        private OpenClCounter(cl_platform_id platform, cl_device_id device, boolean selectedGpu) {
            this.platform = platform;
            this.device = device;
            this.selectedGpu = selectedGpu;
            this.platformName = infoString(platform, CL_PLATFORM_NAME);
            this.deviceName = infoString(device, CL_DEVICE_NAME);

            cl_context_properties properties = new cl_context_properties();
            properties.addProperty(CL_CONTEXT_PLATFORM, platform);
            this.context = clCreateContext(properties, 1, new cl_device_id[]{device}, null, null, null);
            this.queue = clCreateCommandQueueWithProperties(context, device, null, null);
            this.program = clCreateProgramWithSource(context, 1, new String[]{KERNEL_SOURCE}, null, null);
            clBuildProgram(program, 0, null, null, null, null);
            this.kernel = clCreateKernel(program, "count_word", null);
        }

        static Optional<OpenClCounter> create() {
            try {
                CL.setExceptionsEnabled(false);
                DeviceSelection gpu = findDevice(CL_DEVICE_TYPE_GPU, true);
                DeviceSelection selected = gpu != null ? gpu : findDevice(CL_DEVICE_TYPE_ALL, false);
                if (selected == null) {
                    return Optional.empty();
                }

                CL.setExceptionsEnabled(true);
                return Optional.of(new OpenClCounter(selected.platform, selected.device, selected.gpu));
            } catch (CLException ex) {
                System.err.println("Falha ao inicializar OpenCL: " + ex.getMessage());
                return Optional.empty();
            } catch (UnsatisfiedLinkError | ExceptionInInitializerError | NoClassDefFoundError ex) {
                System.err.println("Falha ao carregar OpenCL/JOCL: " + ex.getMessage());
                return Optional.empty();
            }
        }

        long count(byte[] text, byte[] word) {
            if (text.length == 0 || word.length == 0 || word.length > text.length) {
                return 0;
            }
            int[] partial = new int[text.length];

            cl_mem textMem = null;
            cl_mem wordMem = null;
            cl_mem partialMem = null;
            try {
                textMem = clCreateBuffer(context, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR,
                        (long) text.length * Sizeof.cl_uchar, Pointer.to(text), null);
                wordMem = clCreateBuffer(context, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR,
                        (long) word.length * Sizeof.cl_uchar, Pointer.to(word), null);
                partialMem = clCreateBuffer(context, CL_MEM_WRITE_ONLY,
                        (long) partial.length * Sizeof.cl_int, null, null);

                clSetKernelArg(kernel, 0, Sizeof.cl_mem, Pointer.to(textMem));
                clSetKernelArg(kernel, 1, Sizeof.cl_int, Pointer.to(new int[]{text.length}));
                clSetKernelArg(kernel, 2, Sizeof.cl_mem, Pointer.to(wordMem));
                clSetKernelArg(kernel, 3, Sizeof.cl_int, Pointer.to(new int[]{word.length}));
                clSetKernelArg(kernel, 4, Sizeof.cl_mem, Pointer.to(partialMem));

                clEnqueueNDRangeKernel(queue, kernel, 1, null, new long[]{text.length}, null, 0, null, null);
                clFinish(queue);
                clEnqueueReadBuffer(queue, partialMem, CL_TRUE, 0,
                        (long) partial.length * Sizeof.cl_int, Pointer.to(partial), 0, null, null);

                long total = 0;
                for (int value : partial) {
                    total += value;
                }
                return total;
            } finally {
                if (partialMem != null) {
                    clReleaseMemObject(partialMem);
                }
                if (wordMem != null) {
                    clReleaseMemObject(wordMem);
                }
                if (textMem != null) {
                    clReleaseMemObject(textMem);
                }
            }
        }

        String deviceLabel() {
            String type = selectedGpu ? "GPU" : "OpenCL fallback";
            return type + " - " + platformName + " / " + deviceName;
        }

        @Override
        public void close() {
            clReleaseKernel(kernel);
            clReleaseProgram(program);
            clReleaseCommandQueue(queue);
            clReleaseContext(context);
        }

        private static DeviceSelection findDevice(long type, boolean gpu) {
            int[] platformCount = new int[1];
            int platformStatus = clGetPlatformIDs(0, null, platformCount);
            if (platformStatus != CL.CL_SUCCESS || platformCount[0] == 0) {
                return null;
            }

            cl_platform_id[] platforms = new cl_platform_id[platformCount[0]];
            clGetPlatformIDs(platforms.length, platforms, null);
            for (cl_platform_id platform : platforms) {
                int[] deviceCount = new int[1];
                int deviceStatus = clGetDeviceIDs(platform, type, 0, null, deviceCount);
                if (deviceStatus != CL.CL_SUCCESS || deviceCount[0] == 0) {
                    continue;
                }

                cl_device_id[] devices = new cl_device_id[deviceCount[0]];
                clGetDeviceIDs(platform, type, devices.length, devices, null);
                return new DeviceSelection(platform, devices[0], gpu);
            }
            return null;
        }

        private static String infoString(cl_platform_id platform, int paramName) {
            long[] size = new long[1];
            clGetPlatformInfo(platform, paramName, 0, null, size);
            byte[] buffer = new byte[(int) size[0]];
            clGetPlatformInfo(platform, paramName, buffer.length, Pointer.to(buffer), null);
            return cString(buffer);
        }

        private static String infoString(cl_device_id device, int paramName) {
            long[] size = new long[1];
            clGetDeviceInfo(device, paramName, 0, null, size);
            byte[] buffer = new byte[(int) size[0]];
            clGetDeviceInfo(device, paramName, buffer.length, Pointer.to(buffer), null);
            return cString(buffer);
        }

        private static String cString(byte[] buffer) {
            int len = 0;
            while (len < buffer.length && buffer[len] != 0) {
                len++;
            }
            return new String(buffer, 0, len, StandardCharsets.UTF_8);
        }

        private record DeviceSelection(cl_platform_id platform, cl_device_id device, boolean gpu) {
        }
    }

    private static final class ChartGenerator {
        private static final String[] PALETTE = {"#2457a6", "#d95f02", "#1b9e77", "#7570b3", "#e7298a", "#66a61e"};

        static void writeCharts(Path chartDir, List<ResultRow> rows) throws IOException {
            List<ResultRow> okRows = rows.stream()
                    .filter(row -> "OK".equals(row.status()))
                    .toList();
            if (okRows.isEmpty()) {
                Files.writeString(chartDir.resolve("sem-dados.txt"), "Nenhuma execucao OK para gerar graficos.\n", StandardCharsets.UTF_8);
                return;
            }
            writeMethodChart(chartDir.resolve("tempo-medio-por-metodo.svg"), okRows);
            writeCpuThreadsChart(chartDir.resolve("parallel-cpu-threads.svg"), okRows);
        }

        private static void writeMethodChart(Path output, List<ResultRow> rows) throws IOException {
            List<String> samples = rows.stream().map(ResultRow::sample).distinct().toList();
            List<String> methods = rows.stream().map(ResultRow::method).distinct().toList();
            Map<String, Double> averages = rows.stream()
                    .collect(Collectors.groupingBy(row -> row.sample() + "\u0000" + row.method(), LinkedHashMap::new,
                            Collectors.averagingDouble(ResultRow::timeMillis)));

            int width = 1100;
            int height = 620;
            int left = 95;
            int right = 40;
            int top = 70;
            int bottom = 155;
            double max = averages.values().stream().mapToDouble(Double::doubleValue).max().orElse(1.0);
            double plotWidth = width - left - right;
            double plotHeight = height - top - bottom;
            double groupWidth = plotWidth / Math.max(1, samples.size());
            double barWidth = groupWidth / (methods.size() + 1.2);

            StringBuilder svg = svgStart(width, height, "Tempo medio por metodo");
            drawAxes(svg, width, height, left, right, top, bottom, max, "Tempo medio (ms)");
            drawLegend(svg, methods, width - 270, 72);

            for (int sampleIndex = 0; sampleIndex < samples.size(); sampleIndex++) {
                String sample = samples.get(sampleIndex);
                double groupX = left + sampleIndex * groupWidth;
                for (int methodIndex = 0; methodIndex < methods.size(); methodIndex++) {
                    String method = methods.get(methodIndex);
                    double value = averages.getOrDefault(sample + "\u0000" + method, 0.0);
                    double barHeight = max == 0 ? 0 : (value / max) * plotHeight;
                    double x = groupX + (methodIndex + 0.45) * barWidth;
                    double y = top + plotHeight - barHeight;
                    svg.append(String.format(Locale.US,
                            "<rect x=\"%.2f\" y=\"%.2f\" width=\"%.2f\" height=\"%.2f\" fill=\"%s\" rx=\"4\"/>%n",
                            x, y, barWidth * 0.82, barHeight, PALETTE[methodIndex % PALETTE.length]));
                    svg.append(String.format(Locale.US,
                            "<text x=\"%.2f\" y=\"%.2f\" font-size=\"11\" text-anchor=\"middle\">%.2f</text>%n",
                            x + barWidth * 0.41, y - 6, value));
                }
                svg.append(String.format(Locale.US,
                        "<text x=\"%.2f\" y=\"%d\" font-size=\"13\" text-anchor=\"middle\" transform=\"rotate(-18 %.2f %d)\">%s</text>%n",
                        groupX + groupWidth / 2, height - 75, groupX + groupWidth / 2, height - 75, escapeXml(sample)));
            }

            svg.append("</svg>\n");
            Files.writeString(output, svg.toString(), StandardCharsets.UTF_8);
        }

        private static void writeCpuThreadsChart(Path output, List<ResultRow> rows) throws IOException {
            List<ResultRow> cpuRows = rows.stream()
                    .filter(row -> row.method().equals("ParallelCPU"))
                    .filter(row -> row.workers() != null && !row.workers().isBlank())
                    .toList();
            if (cpuRows.isEmpty()) {
                Files.writeString(output, "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"700\" height=\"160\"><text x=\"20\" y=\"80\">Sem dados ParallelCPU</text></svg>", StandardCharsets.UTF_8);
                return;
            }

            List<String> samples = cpuRows.stream().map(ResultRow::sample).distinct().toList();
            List<Integer> workers = cpuRows.stream()
                    .map(row -> Integer.parseInt(row.workers()))
                    .distinct()
                    .sorted()
                    .toList();
            Map<String, Double> averages = cpuRows.stream()
                    .collect(Collectors.groupingBy(row -> row.sample() + "\u0000" + row.workers(), LinkedHashMap::new,
                            Collectors.averagingDouble(ResultRow::timeMillis)));

            int width = 1100;
            int height = 620;
            int left = 95;
            int right = 55;
            int top = 70;
            int bottom = 115;
            double max = averages.values().stream().mapToDouble(Double::doubleValue).max().orElse(1.0);
            double plotWidth = width - left - right;
            double plotHeight = height - top - bottom;
            double xStep = workers.size() <= 1 ? plotWidth : plotWidth / (workers.size() - 1);

            StringBuilder svg = svgStart(width, height, "ParallelCPU por numero de threads");
            drawAxes(svg, width, height, left, right, top, bottom, max, "Tempo medio (ms)");
            drawLegend(svg, samples, width - 320, 72);

            for (int workerIndex = 0; workerIndex < workers.size(); workerIndex++) {
                double x = left + workerIndex * xStep;
                svg.append(String.format(Locale.US,
                        "<text x=\"%.2f\" y=\"%d\" font-size=\"13\" text-anchor=\"middle\">%d</text>%n",
                        x, height - 70, workers.get(workerIndex)));
                svg.append(String.format(Locale.US,
                        "<line x1=\"%.2f\" y1=\"%d\" x2=\"%.2f\" y2=\"%d\" stroke=\"#e8edf3\"/>%n",
                        x, top, x, height - bottom));
            }
            svg.append(String.format(Locale.US,
                    "<text x=\"%.2f\" y=\"%d\" font-size=\"14\" text-anchor=\"middle\">Threads usadas no ParallelCPU</text>%n",
                    left + plotWidth / 2, height - 28));

            for (int sampleIndex = 0; sampleIndex < samples.size(); sampleIndex++) {
                String sample = samples.get(sampleIndex);
                String color = PALETTE[sampleIndex % PALETTE.length];
                StringBuilder points = new StringBuilder();
                for (int workerIndex = 0; workerIndex < workers.size(); workerIndex++) {
                    int worker = workers.get(workerIndex);
                    double value = averages.getOrDefault(sample + "\u0000" + worker, 0.0);
                    double x = left + workerIndex * xStep;
                    double y = top + plotHeight - (max == 0 ? 0 : (value / max) * plotHeight);
                    points.append(String.format(Locale.US, "%.2f,%.2f ", x, y));
                }
                svg.append(String.format("<polyline points=\"%s\" fill=\"none\" stroke=\"%s\" stroke-width=\"3\"/>%n", points, color));
                for (int workerIndex = 0; workerIndex < workers.size(); workerIndex++) {
                    int worker = workers.get(workerIndex);
                    double value = averages.getOrDefault(sample + "\u0000" + worker, 0.0);
                    double x = left + workerIndex * xStep;
                    double y = top + plotHeight - (max == 0 ? 0 : (value / max) * plotHeight);
                    svg.append(String.format(Locale.US,
                            "<circle cx=\"%.2f\" cy=\"%.2f\" r=\"5\" fill=\"%s\"/>%n",
                            x, y, color));
                    svg.append(String.format(Locale.US,
                            "<text x=\"%.2f\" y=\"%.2f\" font-size=\"11\" text-anchor=\"middle\">%.2f</text>%n",
                            x, y - 10, value));
                }
            }

            svg.append("</svg>\n");
            Files.writeString(output, svg.toString(), StandardCharsets.UTF_8);
        }

        private static StringBuilder svgStart(int width, int height, String title) {
            StringBuilder svg = new StringBuilder();
            svg.append(String.format("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"%d\" height=\"%d\" viewBox=\"0 0 %d %d\">%n", width, height, width, height));
            svg.append("<rect width=\"100%\" height=\"100%\" fill=\"#f8fafc\"/>\n");
            svg.append(String.format("<text x=\"%d\" y=\"38\" font-size=\"24\" font-family=\"Georgia, serif\" font-weight=\"700\" fill=\"#14213d\">%s</text>%n", 42, escapeXml(title)));
            svg.append("<style>text{font-family:Arial,sans-serif;fill:#1f2937}.axis{stroke:#334155;stroke-width:1.5}.grid{stroke:#d7dee8;stroke-width:1}</style>\n");
            return svg;
        }

        private static void drawAxes(StringBuilder svg, int width, int height, int left, int right, int top, int bottom,
                                     double max, String yLabel) {
            double plotHeight = height - top - bottom;
            double plotWidth = width - left - right;
            svg.append(String.format(Locale.US,
                    "<line class=\"axis\" x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\"/>%n",
                    left, height - bottom, width - right, height - bottom));
            svg.append(String.format(Locale.US,
                    "<line class=\"axis\" x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\"/>%n",
                    left, top, left, height - bottom));
            for (int tick = 0; tick <= 5; tick++) {
                double value = max * tick / 5.0;
                double y = top + plotHeight - (plotHeight * tick / 5.0);
                svg.append(String.format(Locale.US,
                        "<line class=\"grid\" x1=\"%d\" y1=\"%.2f\" x2=\"%.2f\" y2=\"%.2f\"/>%n",
                        left, y, left + plotWidth, y));
                svg.append(String.format(Locale.US,
                        "<text x=\"%d\" y=\"%.2f\" font-size=\"12\" text-anchor=\"end\">%.2f</text>%n",
                        left - 10, y + 4, value));
            }
            svg.append(String.format(Locale.US,
                    "<text x=\"24\" y=\"%.2f\" font-size=\"14\" text-anchor=\"middle\" transform=\"rotate(-90 24 %.2f)\">%s</text>%n",
                    top + plotHeight / 2, top + plotHeight / 2, escapeXml(yLabel)));
        }

        private static void drawLegend(StringBuilder svg, List<String> labels, int x, int y) {
            for (int i = 0; i < labels.size(); i++) {
                int itemY = y + i * 24;
                svg.append(String.format(Locale.US,
                        "<rect x=\"%d\" y=\"%d\" width=\"16\" height=\"16\" fill=\"%s\" rx=\"3\"/>%n",
                        x, itemY - 13, PALETTE[i % PALETTE.length]));
                svg.append(String.format(Locale.US,
                        "<text x=\"%d\" y=\"%d\" font-size=\"13\">%s</text>%n",
                        x + 24, itemY, escapeXml(labels.get(i))));
            }
        }

        private static String escapeXml(String value) {
            return value.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;");
        }
    }
}
