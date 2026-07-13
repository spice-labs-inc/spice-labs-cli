// SPDX-License-Identifier: Apache-2.0
/* Copyright 2025 Spice Labs, Inc. & Contributors */

package io.spicelabs.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code spice survey static <subject> <input>} — static crypto analysis via
 * sassafras.
 *
 * <p>This survey type is present <strong>only</strong> when the allspice fat JAR
 * (which bundles sassafras) is available at runtime (enterprise image). It
 * analyzes JVM bytecode for JCA crypto usage, classifies findings on PQC status
 * and crypto-agility, and writes a JSON report.
 *
 * <p>Sassafras is invoked in-process via the allspice classloader. The
 * {@code SassafrasCli} picocli command is loaded reflectively and executed with
 * the input JAR path and output file.
 *
 * <p>This is the static sibling of {@code spice survey runtime} (which uses ancho
 * for runtime crypto observation via JFR). {@code inventory} produces a
 * dependency graph (goatrodeo); {@code static} produces a crypto findings report.
 */
@Command(
    name = "static",
    description = "Static crypto analysis of JVM bytecode via sassafras (enterprise)",
    mixinStandardHelpOptions = true,
    footer = {
        "",
        "Examples:",
        "  spice survey static my-app ./app.jar",
        "  spice survey static my-app ./app.jar --output ./out/lintium.json",
        "",
        "This command requires the enterprise image (sassafras).",
        "It is not available in the OSS build.",
        ""
    }
)
public class SurveyStaticCommand implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(SurveyStaticCommand.class);

    private static final String SASSAFRAS_CLI_CLASS = "io.spicelabs.sassafras.cli.SassafrasCli";

    @Parameters(index = "0", description = "Label identifying the system being surveyed")
    String subject;

    @Parameters(index = "1", description = "Path to JAR or WAR file to analyze")
    Path input;

    @Option(names = "--output", description = "Output JSON file path (default: ./<subject>-lintium.json)")
    Path output;

    @Option(names = "--max-classes", description = "Maximum classes to analyze (default: 50000)")
    Integer maxClasses;

    @Option(names = "--max-depth", description = "Maximum call chain depth (default: 20)")
    Integer maxDepth;

    @Option(names = "--timeout", description = "Analysis timeout in seconds (default: 1800)")
    Integer timeoutSeconds;

    @Option(names = "--cbom", description = "Generate a CycloneDX CBOM via report_cli (federal image only)")
    boolean cbom;

    @Option(names = "--adg-dir", description = "Directory containing goatrodeo ADG output (items_*.json) for CBOM generation")
    Path adgDir;

    @Option(names = "--cbom-output", description = "Output CBOM file path (default: ./<subject>-cbom.json)")
    Path cbomOutput;

    @Override
    public Integer call() {
        AllspiceLoader loader = AllspiceLoader.getInstance();
        if (loader == null) {
            log.error("sassafras is not available. This command requires the enterprise image.");
            return 1;
        }

        if (!Files.isRegularFile(input)) {
            log.error("Input must be a JAR or WAR file: {}", input);
            return 1;
        }

        Path outputPath = output;
        if (outputPath == null) {
            outputPath = Path.of(subject + "-lintium.json");
        }

        log.info("🌶️  Static crypto analysis: {} ({})", subject, input);

        try {
            // Load SassafrasCli from the allspice classloader
            Class<?> cliClass = loader.getClassLoader().loadClass(SASSAFRAS_CLI_CLASS);
            Object cliInstance = cliClass.getDeclaredConstructor().newInstance();

            // Build the picocli CommandLine from the loaded class
            CommandLine sassafrasCmd = new CommandLine(cliInstance);

            // Construct args
            java.util.List<String> args = new java.util.ArrayList<>();
            args.add(input.toString());
            args.add("-o");
            args.add(outputPath.toString());
            if (maxClasses != null) {
                args.add("--max-classes");
                args.add(maxClasses.toString());
            }
            if (maxDepth != null) {
                args.add("--max-depth");
                args.add(maxDepth.toString());
            }
            if (timeoutSeconds != null) {
                args.add("--timeout");
                args.add(timeoutSeconds.toString());
            }

            int exitCode = sassafrasCmd.execute(args.toArray(new String[0]));
            if (exitCode != 0) {
                log.error("❌ Sassafras analysis failed with exit code {}", exitCode);
                return exitCode;
            }
            log.info("✅ Sassafras analysis complete. Report written to {}", outputPath);

            // Generate CBOM if requested and report_cli is available (federal)
            if (cbom) {
                exitCode = generateCbom(loader, outputPath);
            }
            return exitCode;
        } catch (Exception e) {
            log.error("Failed to run sassafras: {}", e.getMessage(), e);
            return 1;
        }
    }

    /**
     * Generate a CycloneDX CBOM via the report_cli binary (federal image only).
     */
    private int generateCbom(AllspiceLoader loader, Path lintiumPath) {
        if (!AllspiceLoader.isReportCliAvailable()) {
            log.error("❌ --cbom requires the federal image (report_cli not found at {})",
                AllspiceLoader.resolveReportCliPath());
            return 1;
        }

        Path cbomPath = cbomOutput != null ? cbomOutput : Path.of(subject + "-cbom.json");

        // report_cli needs a cluster-json-dir (ADG output). If not provided,
        // use the lintium file's parent dir — the CBOM will contain crypto
        // findings only (no dependency graph).
        Path clusterDir = adgDir;
        if (clusterDir == null) {
            clusterDir = lintiumPath.getParent() != null ? lintiumPath.getParent() : Path.of(".");
        }

        String reportCli = AllspiceLoader.resolveReportCliPath();
        String rogues = "/opt/allspice/rogues_gallery.json";

        log.info("📋 Generating CBOM via report_cli...");

        ProcessBuilder pb = new ProcessBuilder(
            reportCli,
            "--format", "cbom",
            "--cluster-json-dir", clusterDir.toString(),
            "--rogues", rogues,
            "--lintium", lintiumPath.toString(),
            "--output", cbomPath.toString(),
            "--log-level", "error"
        );
        pb.inheritIO();

        try {
            int exitCode = pb.start().waitFor();
            if (exitCode == 0) {
                log.info("✅ CBOM written to {}", cbomPath);
            } else {
                log.error("❌ report_cli failed with exit code {}", exitCode);
            }
            return exitCode;
        } catch (Exception e) {
            log.error("Failed to invoke report_cli: {}", e.getMessage(), e);
            return 1;
        }
    }
}
