import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.io.PrintWriter;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectPackage;
import static org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder.request;

/**
 * Surefire 的沙箱兜底 runner：Surefire 必须通过 cmd.exe 建管道才能 fork 测试 JVM，
 * 受限沙箱会拒绝（CreatePipe error=5），于是改用 JUnit Platform Launcher 在进程内跑。
 * 由 tools/run-tests.ps1 编译并调用，不参与 Maven 构建。
 *
 * 用法: java -javaagent:<mockito-core.jar> -cp <...> JUnitRunner <fqcn|package>...
 *
 * 失败摘要默认会被截断到看不出原因，所以这里自己把每个失败的异常与首个项目内栈帧打进
 * .scratch/test-runner/failures.txt（该目录被 .gitignore 覆盖）。
 */
public final class JUnitRunner {

    public static void main(String[] args) throws Exception {
        var builder = request();
        for (String arg : args) {
            if (arg == null || arg.isBlank()) {
                continue;
            }
            int dot = arg.lastIndexOf('.');
            boolean looksLikeClass = dot > 0 && dot < arg.length() - 1
                    && Character.isUpperCase(arg.charAt(dot + 1));
            builder.selectors(looksLikeClass ? selectClass(arg) : selectPackage(arg));
        }
        LauncherDiscoveryRequest discovery = builder.build();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        Launcher launcher = LauncherFactory.create();
        launcher.execute(discovery, listener);

        TestExecutionSummary summary = listener.getSummary();
        StringBuilder report = new StringBuilder();
        report.append("found=").append(summary.getTestsFoundCount())
                .append(" succeeded=").append(summary.getTestsSucceededCount())
                .append(" failed=").append(summary.getTestsFailedCount())
                .append(" skipped=").append(summary.getTestsSkippedCount()).append('\n');
        for (TestExecutionSummary.Failure failure : summary.getFailures()) {
            report.append("=== ").append(failure.getTestIdentifier().getDisplayName()).append('\n')
                    .append(failure.getException()).append('\n');
            for (StackTraceElement frame : failure.getException().getStackTrace()) {
                if (frame.getClassName().contains("edgegateway")) {
                    report.append("  at ").append(frame).append('\n');
                }
            }
        }
        String reportPath = System.getProperty("testReport", ".scratch/test-runner/failures.txt");
        java.nio.file.Path path = java.nio.file.Path.of(reportPath).toAbsolutePath();
        java.nio.file.Files.createDirectories(path.getParent());
        java.nio.file.Files.writeString(path, report.toString(), java.nio.charset.StandardCharsets.UTF_8);

        PrintWriter out = new PrintWriter(System.out, true);
        summary.printTo(out);
        System.out.println("RESULT found=" + summary.getTestsFoundCount()
                + " succeeded=" + summary.getTestsSucceededCount()
                + " failed=" + summary.getTestsFailedCount()
                + " | 失败详情: " + path);
        System.exit(summary.getTotalFailureCount() > 0 ? 1 : 0);
    }
}
