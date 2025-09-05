import React, { useState, useEffect } from 'react';
import { Text, Box, useInput, useApp } from 'ink';
import SelectInput from 'ink-select-input';
import TextInput from 'ink-text-input';
import Spinner from 'ink-spinner';
import { ModuleInfo, getAllStringsForLocale, loadModules } from '../module-loader';
import { I18nConfig, getStringResourcePath } from '../config';
import { batchTranslate, TranslationProgress } from '../translator';
import { parseXmlFile, writeXmlFile, mergeTranslations, StringResource } from '../xml-parser';

// Utility functions for display width calculation
function getCharWidth(char: string): number {
  const code = char.codePointAt(0) || 0;
  if (
    (code >= 0x4E00 && code <= 0x9FFF) ||  // CJK Unified Ideographs
    (code >= 0x3400 && code <= 0x4DBF) ||  // CJK Extension A
    (code >= 0x20000 && code <= 0x2A6DF) || // CJK Extension B
    (code >= 0x2A700 && code <= 0x2B73F) || // CJK Extension C
    (code >= 0x2B740 && code <= 0x2B81F) || // CJK Extension D
    (code >= 0x2B820 && code <= 0x2CEAF) || // CJK Extension E
    (code >= 0x3000 && code <= 0x303F) ||  // CJK Symbols and Punctuation
    (code >= 0xFF00 && code <= 0xFFEF)     // Halfwidth and Fullwidth Forms
  ) {
    return 2;
  }
  return 1;
}

function getDisplayWidth(str: string): number {
  return Array.from(str).reduce((width, char) => {
    return width + getCharWidth(char);
  }, 0);
}

function truncateByDisplayWidth(str: string, maxWidth: number): string {
  let width = 0;
  let result = '';
  for (const char of str) {
    const charWidth = getCharWidth(char);
    if (width + charWidth > maxWidth - 3) {
      return result + '...';
    }
    width += charWidth;
    result += char;
  }
  return result;
}

function createLanguageSelectItems(targets: string[], moduleStats: any) {
  return targets.map(locale => {
    const stats = moduleStats[locale];
    const completionBar = '█'.repeat(Math.floor(stats.percentage / 10)) +
                         '░'.repeat(10 - Math.floor(stats.percentage / 10));

    return {
      label: `${locale.padEnd(8)} [${completionBar}] ${stats.translated}/${stats.total} (${stats.percentage.toFixed(1)}%)`,
      value: locale
    };
  });
}

interface TranslationTableProps {
  module: ModuleInfo;
  config: I18nConfig;
  onBack: () => void;
  onModuleUpdate: (module: ModuleInfo) => void;
}

type ViewMode = 'language-select' | 'table' | 'edit' | 'translating';

interface TableRow {
  key: string;
  defaultValue: string;
  translatedValue?: string;
  isTranslated: boolean;
  translatable: boolean;
}

export function TranslationTable({ module, config, onBack, onModuleUpdate }: TranslationTableProps) {
  const { exit } = useApp();
  const [viewMode, setViewMode] = useState<ViewMode>('language-select');
  const [selectedLocale, setSelectedLocale] = useState<string>('');
  const [tableData, setTableData] = useState<TableRow[]>([]);
  const [filteredData, setFilteredData] = useState<TableRow[]>([]);
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedRowIndex, setSelectedRowIndex] = useState(0);
  const [editingValue, setEditingValue] = useState('');
  const [translationProgress, setTranslationProgress] = useState<TranslationProgress | null>(null);
  const [showMissingOnly, setShowMissingOnly] = useState(false);
  const [scrollOffset, setScrollOffset] = useState(0);

  // Load table data when locale is selected
  useEffect(() => {
    if (selectedLocale && viewMode === 'table') {
      const data = getAllStringsForLocale(module, selectedLocale);
      setTableData(data);
    }
  }, [selectedLocale, module, viewMode]);

  // Filter table data based on search and missing filter
  useEffect(() => {
    let filtered = tableData;

    if (showMissingOnly) {
      filtered = filtered.filter(row => row.translatable && !row.isTranslated);
    }

    if (searchQuery) {
      const query = searchQuery.toLowerCase();
      filtered = filtered.filter(row =>
        row.key.toLowerCase().includes(query) ||
        row.defaultValue.toLowerCase().includes(query) ||
        (row.translatedValue && row.translatedValue.toLowerCase().includes(query))
      );
    }

    setFilteredData(filtered);
    setSelectedRowIndex(0);
    setScrollOffset(0);
  }, [tableData, searchQuery, showMissingOnly]);

  const handleKeyboardInput = (input: string, key: any) => {
    if (viewMode === 'table') {
      const maxVisibleRows = 15;
      
      if (key.upArrow && selectedRowIndex > 0) {
        const newIndex = selectedRowIndex - 1;
        setSelectedRowIndex(newIndex);
        
        if (newIndex < scrollOffset) {
          setScrollOffset(newIndex);
        }
      } else if (key.downArrow && selectedRowIndex < filteredData.length - 1) {
        const newIndex = selectedRowIndex + 1;
        setSelectedRowIndex(newIndex);
        
        if (newIndex >= scrollOffset + maxVisibleRows) {
          setScrollOffset(newIndex - maxVisibleRows + 1);
        }
      } else if (input === 'e' && filteredData[selectedRowIndex]) {
        const row = filteredData[selectedRowIndex];
        setEditingValue(row.translatedValue || row.defaultValue);
        setViewMode('edit');
      } else if (input === 't') {
        handleTranslateMissing();
      } else if (input === 'f') {
        setShowMissingOnly(!showMissingOnly);
      } else if (input === 'q' || key.escape) {
        setViewMode('language-select');
      }
    } else if (viewMode === 'edit') {
      if (key.escape) {
        setViewMode('table');
      }
    }

    if (key.ctrl && input === 'c') {
      exit();
    }
  };

  useInput(handleKeyboardInput);

  const handleTranslateMissing = async () => {
    const missingStrings = filteredData
      .filter(row => row.translatable && !row.isTranslated)
      .map(row => ({
        key: row.key,
        value: row.defaultValue,
        translatable: row.translatable
      }));

    if (missingStrings.length === 0) {
      return;
    }

    setViewMode('translating');

    try {
      const translatedStrings = await batchTranslate(
        missingStrings,
        selectedLocale,
        config,
        setTranslationProgress
      );

      // Update table data with translated results
      const updatedTableData = tableData.map(row => {
        const translation = translatedStrings.find(ts => ts.key === row.key);
        if (translation && translation.value !== row.defaultValue) {
          return {
            ...row,
            translatedValue: translation.value,
            isTranslated: true
          };
        }
        return row;
      });
      
      setTableData(updatedTableData);

      // Save translations to XML file
      await saveTranslations(translatedStrings);

      // Reload module data
      const updatedModules = await loadModules(config);
      const updatedModule = updatedModules.find(m => m.moduleName === module.moduleName);
      if (updatedModule) {
        onModuleUpdate(updatedModule);
      }

      setViewMode('table');
    } catch (error) {
      console.error('Translation failed:', error);
      setViewMode('table');
    }
  };

  const saveTranslations = async (newTranslations?: StringResource[]) => {
    const translationPath = getStringResourcePath(module.modulePath, selectedLocale);
    const existingTranslations = await parseXmlFile(translationPath);

    let translationsToSave: StringResource[];

    if (newTranslations) {
      // Use the provided new translations
      translationsToSave = newTranslations;
    } else {
      // Get current translations from table (for manual edits)
      translationsToSave = tableData
        .filter(row => row.isTranslated && row.translatedValue)
        .map(row => ({
          key: row.key,
          value: row.translatedValue!,
          translatable: row.translatable
        }));
    }

    const mergedTranslations = mergeTranslations(existingTranslations, translationsToSave);
    await writeXmlFile(translationPath, mergedTranslations);
  };

  const handleEditSave = async (newValue: string) => {
    const row = filteredData[selectedRowIndex];
    if (!row) return;

    // Update table data
    const updatedTableData = tableData.map(r =>
      r.key === row.key ? { ...r, translatedValue: newValue, isTranslated: true } : r
    );
    setTableData(updatedTableData);

    // Save to file
    await saveTranslations();

    setViewMode('table');
  };

  const renderTranslationTable = (visibleRows: TableRow[], selectedRowIndex: number, scrollOffset: number) => {
    return (
      <Box flexDirection="column">
        <Box>
          <Box width={20}>
            <Text bold color="blue">Key</Text>
          </Box>
          <Box width={20}>
            <Text bold color="blue">Default</Text>
          </Box>
          <Box width={20}>
            <Text bold color="blue">Translation</Text>
          </Box>
          <Box width={8}>
            <Text bold color="blue">Status</Text>
          </Box>
        </Box>
        
        {visibleRows.map((row, index) => {
          const absoluteIndex = scrollOffset + index;
          const isSelected = absoluteIndex === selectedRowIndex;
          const statusIcon = !row.translatable ? '🚫' : row.isTranslated ? '✅' : '❌';
          
          return (
            <Box key={row.key} backgroundColor={isSelected ? 'blue' : undefined} width={68}>
              <Box width={20} flexShrink={0}>
                <Text color={isSelected ? 'white' : 'white'}>
                  {truncateByDisplayWidth(row.key, 18)}
                </Text>
              </Box>
              <Box width={20} flexShrink={0}>
                <Text color={isSelected ? 'white' : 'yellow'}>
                  {truncateByDisplayWidth(row.defaultValue, 18)}
                </Text>
              </Box>
              <Box width={20} flexShrink={0}>
                <Text color={isSelected ? 'white' : 'white'}>
                  {truncateByDisplayWidth(row.translatedValue || '', 18)}
                </Text>
              </Box>
              <Box width={8} flexShrink={0}>
                <Text color={isSelected ? 'white' : 'white'}>{statusIcon}</Text>
              </Box>
            </Box>
          );
        })}
      </Box>
    );
  };

  const renderLanguageSelection = () => {
    const languageItems = createLanguageSelectItems(config.targets, module.stats);

    return (
      <Box flexDirection="column">
        <Box marginBottom={1}>
          <Text bold color="cyan">📝 {module.moduleName} - Select Language</Text>
        </Box>

        <SelectInput
          items={languageItems}
          onSelect={(item) => {
            setSelectedLocale(item.value);
            setViewMode('table');
          }}
        />

        <Box marginTop={1}>
          <Text color="gray" dimColor>
            Press Enter to select, q to go back
          </Text>
        </Box>
      </Box>
    );
  };

  if (viewMode === 'language-select') {
    return renderLanguageSelection();
  }

  // Translation progress view
  if (viewMode === 'translating' && translationProgress) {
    return (
      <Box flexDirection="column">
        <Box marginBottom={1}>
          <Text bold color="cyan">
            <Spinner type="dots" /> Translating to {selectedLocale}
          </Text>
        </Box>

        <Box marginBottom={1}>
          <Text>
            Progress: {translationProgress.current}/{translationProgress.total}
          </Text>
        </Box>

        <Box marginBottom={1}>
          <Text color={translationProgress.status === 'error' ? 'red' : 'yellow'}>
            {translationProgress.status === 'translating' && `🔄 Translating: ${translationProgress.key}`}
            {translationProgress.status === 'completed' && `✅ Completed: ${translationProgress.key}`}
            {translationProgress.status === 'error' && `❌ Error: ${translationProgress.key} - ${translationProgress.error}`}
          </Text>
        </Box>

        <Box>
          <Text color="gray" dimColor>
            Please wait... Press Ctrl+C to cancel
          </Text>
        </Box>
      </Box>
    );
  }

  // Edit mode
  if (viewMode === 'edit') {
    const row = filteredData[selectedRowIndex];
    if (!row) return null;

    return (
      <Box flexDirection="column">
        <Box marginBottom={1}>
          <Text bold color="cyan">✏️  Edit Translation - {selectedLocale}</Text>
        </Box>

        <Box marginBottom={1}>
          <Text bold>Key: </Text>
          <Text color="gray">{row.key}</Text>
        </Box>

        <Box marginBottom={1}>
          <Text bold>Default: </Text>
          <Text color="yellow">{row.defaultValue}</Text>
        </Box>

        <Box marginBottom={1}>
          <Text bold>Translation: </Text>
        </Box>

        <Box marginBottom={1}>
          <TextInput
            value={editingValue}
            onChange={setEditingValue}
            onSubmit={handleEditSave}
          />
        </Box>

        <Box>
          <Text color="gray" dimColor>
            Press Enter to save, Esc to cancel
          </Text>
        </Box>
      </Box>
    );
  }

  // Table view
  const stats = module.stats[selectedLocale] ?? { total: 0, translated: 0, missing: 0, percentage: 0 };
  const maxVisibleRows = 15;
  const visibleRows = filteredData.slice(scrollOffset, scrollOffset + maxVisibleRows);



  return (
    <Box flexDirection="column">
      <Box marginBottom={1}>
        <Text bold color="cyan">
          📋 {module.moduleName} - {selectedLocale}
        </Text>
        <Text color="gray"> ({stats.translated}/{stats.total}, {stats.percentage.toFixed(1)}%)</Text>
      </Box>

      {searchQuery && (
        <Box marginBottom={1}>
          <Text color="yellow">🔍 Search: "{searchQuery}" ({filteredData.length} results)</Text>
        </Box>
      )}

      {showMissingOnly && (
        <Box marginBottom={1}>
          <Text color="yellow">🔍 Showing only missing translations ({filteredData.length} items)</Text>
        </Box>
      )}

      {renderTranslationTable(visibleRows, selectedRowIndex, scrollOffset)}

      <Box marginTop={1} flexDirection="column">
        {filteredData.length > maxVisibleRows && (
          <Box marginBottom={1}>
            <Text color="gray" dimColor>
              Showing {scrollOffset + 1}-{Math.min(scrollOffset + maxVisibleRows, filteredData.length)} of {filteredData.length}
            </Text>
          </Box>
        )}
        <Box>
          <Text color="gray" dimColor>
            ↑↓: Navigate | e: Edit | t: Translate missing | f: Toggle missing filter | q: Back
          </Text>
        </Box>
      </Box>
    </Box>
  );
}
