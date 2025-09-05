import { google } from '@ai-sdk/google';
import { createOpenAICompatible } from '@ai-sdk/openai-compatible';
import { generateText } from 'ai';
import { StringResource } from './xml-parser';
import { I18nConfig } from './config';
import * as fs from 'fs';
import * as path from 'path';

export interface TranslationProgress {
  current: number;
  total: number;
  key: string;
  status: 'translating' | 'completed' | 'error';
  error?: string;
}

export type ProgressCallback = (progress: TranslationProgress) => void;

// Logging utility
const LOG_FILE = path.join(process.cwd(), 'logs.txt');

function logToFile(message: string): void {
  const timestamp = new Date().toISOString();
  const logEntry = `[${timestamp}] ${message}\n`;
  try {
    fs.appendFileSync(LOG_FILE, logEntry, 'utf8');
  } catch (error) {
    console.error('Failed to write to log file:', error);
  }
}

const LANGUAGE_NAMES: Record<string, string> = {
  'zh': 'Simplified Chinese (简体中文)',
  'zh-rTW': 'Traditional Chinese (繁體中文)',
  'ja': 'Japanese (日本語)',
  'ko': 'Korean (한국어)',
  'es': 'Spanish (Español)',
  'fr': 'French (Français)',
  'de': 'German (Deutsch)',
  'it': 'Italian (Italiano)',
  'pt': 'Portuguese (Português)',
  'ru': 'Russian (Русский)',
};

function getLanguageName(locale: string): string {
  return LANGUAGE_NAMES[locale] || locale;
}

function getModel(config: I18nConfig) {
  const openaiProvider = createOpenAICompatible({
    apiKey: process.env.OPENAI_API_KEY,
    baseURL: process.env.OPENAI_BASE_URL || 'https://api.openai.com/v1',
    name: 'openai',
  })

  switch (config.provider.type.toLowerCase()) {
    case 'google':
    case 'gemini':
      return google(config.provider.model);
    case 'openai':
      return openaiProvider(config.provider.model);
    default:
      throw new Error(`Unsupported provider: ${config.provider.type}`);
  }
}

export async function translateString(
  text: string,
  targetLocale: string,
  config: I18nConfig,
  context?: string
): Promise<string> {
  logToFile(`Starting translation - Target: ${targetLocale}, Text: "${text}"`);

  try {
    const model = getModel(config);
    const targetLanguage = getLanguageName(targetLocale);

    const prompt = `Translate the following Android app string resource to ${targetLanguage}.

Context: This is a string resource from an Android LLM chat client app called RikkaHub.
${context ? `Additional context: ${context}` : ''}

Original text: "${text}"

Rules:
1. Keep Android string formatting like %1$d, %1$s, \\n, \\', etc. unchanged
2. Preserve XML entities like &amp;, &lt;, &gt;
3. Maintain the natural flow and meaning appropriate for the target language
4. For UI elements, use terms commonly used in mobile apps in that language
5. Return ONLY the translated text, no explanations or quotes

Translation:`;

    logToFile(`Using provider: ${config.provider.type}, model: ${config.provider.model}`);

    const result = await generateText({
      model,
      prompt,
      temperature: 0.3,
      providerOptions: {
        provider: {
            sort: 'throughput'
        }
      }
    });

    const translatedText = result.text.trim();
    logToFile(`Translation completed - Result: "${translatedText}"`);

    return translatedText;
  } catch (error) {
    const errorMessage = error instanceof Error ? error.message : 'Unknown error';
    logToFile(`Translation failed - Error: ${errorMessage}`);
    throw error;
  }
}

export async function batchTranslate(
  strings: StringResource[],
  targetLocale: string,
  config: I18nConfig,
  onProgress?: ProgressCallback
): Promise<StringResource[]> {
  const total = strings.length;
  const concurrency = Math.max(1, config.concurrency ?? 1);
  logToFile(
    `Starting batch translation - Target: ${targetLocale}, Total strings: ${total}, Concurrency: ${concurrency}`
  );

  const results: StringResource[] = new Array(total);
  let successCount = 0;
  let errorCount = 0;
  let startedCount = 0;
  let completedCount = 0;

  // Shared index for workers
  let index = 0;

  async function worker(workerId: number) {
    while (true) {
      const currentIndex = index;
      if (currentIndex >= total) break;
      index++;

      const stringResource = strings[currentIndex]!;

      try {
        logToFile(
          `Worker ${workerId} processing ${currentIndex + 1}/${total} - Key: ${stringResource.key}`
        );

        startedCount++;
        onProgress?.({
          current: completedCount,
          total,
          key: stringResource.key,
          status: 'translating'
        });

        const translatedValue = await translateString(
          stringResource.value,
          targetLocale,
          config,
          `Key: ${stringResource.key}`
        );

        results[currentIndex] = {
          key: stringResource.key,
          value: translatedValue,
          translatable: stringResource.translatable
        };

        successCount++;
        completedCount++;
        logToFile(`Successfully translated key: ${stringResource.key}`);

        onProgress?.({
          current: completedCount,
          total,
          key: stringResource.key,
          status: 'completed'
        });

        // Small delay to avoid hitting rate limits too fast across workers
        await new Promise(resolve => setTimeout(resolve, 100));
      } catch (error) {
        const errorMessage = error instanceof Error ? error.message : 'Unknown error';
        errorCount++;
        completedCount++;
        logToFile(`Failed to translate key: ${stringResource.key} - Error: ${errorMessage}`);

        onProgress?.({
          current: completedCount,
          total,
          key: stringResource.key,
          status: 'error',
          error: errorMessage
        });

        // For errors, keep the original text as fallback at the same index
        results[currentIndex] = {
          key: stringResource.key,
          value: stringResource.value,
          translatable: stringResource.translatable
        };
      }
    }
  }

  const workerCount = Math.min(concurrency, total);
  const workers = Array.from({ length: workerCount }, (_, i) => worker(i + 1));
  await Promise.all(workers);

  logToFile(
    `Batch translation completed - Total: ${total}, Success: ${successCount}, Errors: ${errorCount}`
  );
  return results;
}
