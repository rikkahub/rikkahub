#!/usr/bin/env bun

import { cac } from "cac";
import { config as loadEnv } from "dotenv";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { loadTraceConfig, resolveTraceCases } from "./config";
import { buildProviderRequest, defaultApiKeyEnv, redactRequest } from "./providers";
import { recordTrace } from "./recorder";

interface CliOptions {
  configPath: string;
  selectedName?: string;
  force: boolean;
  dryRun: boolean;
  list: boolean;
}

interface CommandOptions {
  case?: string;
  force?: boolean;
  dryRun?: boolean;
  list?: boolean;
}

async function run(options: CliOptions): Promise<void> {
  const configPath = resolve(options.configPath);
  loadEnvironment(configPath);
  const config = await loadTraceConfig(configPath);
  const traces = resolveTraceCases(config, configPath, options.selectedName);

  if (options.list) {
    for (const trace of traces) {
      console.log(`${trace.name}\t${trace.provider}\t${trace.outputPath}`);
    }
    return;
  }

  if (options.dryRun) {
    for (const trace of traces) {
      const envName = trace.apiKeyEnv ?? defaultApiKeyEnv(trace.provider);
      const request = redactRequest(buildProviderRequest(trace, "<redacted>"));
      console.log(JSON.stringify({
        name: trace.name,
        provider: trace.provider,
        apiKeyEnv: envName,
        output: trace.outputPath,
        request,
      }, null, 2));
    }
    return;
  }

  for (const trace of traces) {
    console.log(`[trace] ${trace.name} (${trace.provider})`);
    const count = await recordTrace(trace, options.force);
    console.log(`[done] ${count} events -> ${trace.outputPath}`);
  }
}

async function main(): Promise<void> {
  const cli = cac("trace-cli");

  cli
    .command("<config>", "Record provider SSE responses as JSONL traces")
    .option("--case <name>", "Only record one trace case")
    .option("--force", "Replace an existing trace after a successful request")
    .option("--dry-run", "Print redacted requests without accessing the network")
    .option("--list", "List selected trace cases")
    .action(async (configPath: string, command: CommandOptions) => {
      await run({
        configPath,
        selectedName: command.case,
        force: command.force ?? false,
        dryRun: command.dryRun ?? false,
        list: command.list ?? false,
      });
    });

  cli.help();
  cli.version("0.1.0");
  cli.parse(process.argv, { run: false });
  await cli.runMatchedCommand();
}

function loadEnvironment(configPath: string): void {
  const cliRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
  const candidates = new Set([
    resolve(cliRoot, ".env"),
    resolve(dirname(configPath), ".env"),
    resolve(process.cwd(), ".env"),
  ]);
  for (const path of candidates) {
    loadEnv({ path, override: false, quiet: true });
  }
}

main().catch((error: unknown) => {
  console.error(error instanceof Error ? (error.stack ?? error.message) : error);
  process.exitCode = 1;
});
