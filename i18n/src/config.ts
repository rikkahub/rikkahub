import { readFileSync } from 'fs';
import { parse } from 'yaml';
import path from 'path';

export interface I18nConfig {
  targets: string[];
  workspaceRoot: string;
  modules: string[];
  // Maximum number of concurrent translation requests
  concurrency?: number;
  provider: {
    type: string;
    model: string;
  };
}

export function loadConfig(configPath: string): I18nConfig {
  const fullPath = path.resolve(configPath);
  const content = readFileSync(fullPath, 'utf-8');
  return parse(content) as I18nConfig;
}

export function getModulePaths(config: I18nConfig): string[] {
  return config.modules.map(module => 
    path.join(config.workspaceRoot, module)
  );
}

export function getStringResourcePath(modulePath: string, locale?: string): string {
  const valuesDir = locale ? `values-${locale}` : 'values';
  return path.join(modulePath, 'src', 'main', 'res', valuesDir, 'strings.xml');
}
