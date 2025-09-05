import React from 'react';
import { Text, Box } from 'ink';
import SelectInput from 'ink-select-input';
import { ModuleInfo } from '../module-loader';
import { I18nConfig } from '../config';

interface ModuleSelectorProps {
  modules: ModuleInfo[];
  config: I18nConfig;
  onSelect: (module: ModuleInfo) => void;
}

export function ModuleSelector({ modules, config, onSelect }: ModuleSelectorProps) {
  const items = modules.map(module => {
    // Calculate overall completion percentage
    const totalStats = config.targets.reduce((acc, locale) => {
      const stats = module.stats[locale] ?? { total: 0, translated: 0, missing: 0, percentage: 0 };
      return {
        total: acc.total + stats.total,
        translated: acc.translated + stats.translated
      };
    }, { total: 0, translated: 0 });
    
    const overallPercentage = totalStats.total > 0 
      ? (totalStats.translated / totalStats.total) * 100 
      : 0;
    
    const completionBar = '█'.repeat(Math.floor(overallPercentage / 10)) + 
                         '░'.repeat(10 - Math.floor(overallPercentage / 10));
    
    return {
      label: `${module.moduleName.padEnd(12)} [${completionBar}] ${overallPercentage.toFixed(1)}%`,
      value: module
    };
  });

  return (
    <Box flexDirection="column">
      <Box marginBottom={1}>
        <Text bold color="cyan">🌐 I18n Translation Manager</Text>
      </Box>
      <Box marginBottom={1}>
        <Text color="gray">Select a module to manage translations:</Text>
      </Box>
      <Box marginBottom={1}>
        <Text color="gray">Target languages: {config.targets.join(', ')}</Text>
      </Box>
      
      <SelectInput items={items} onSelect={(item) => onSelect(item.value)} />
      
      <Box marginTop={1}>
        <Text color="gray" dimColor>
          Use ↑↓ to navigate, Enter to select
        </Text>
      </Box>
    </Box>
  );
}
