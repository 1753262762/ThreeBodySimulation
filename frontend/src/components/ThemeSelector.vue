<script setup lang="ts">
import { usePreferencesStore } from '../stores/preferences'
import { THEME_PREFERENCE_LABELS, THEME_PREFERENCE_VALUES, type ThemePreference } from '../lib/theme'

const preferences = usePreferencesStore()

function setTheme(value: string): void {
  preferences.setThemePreference(value as ThemePreference)
}
</script>

<template>
  <div class="theme-selector" role="group" aria-label="主题">
    <button
      v-for="value in THEME_PREFERENCE_VALUES"
      :key="value"
      type="button"
      :class="{ active: preferences.themePreference === value }"
      :aria-pressed="preferences.themePreference === value"
      @click="setTheme(value)"
    >
      {{ THEME_PREFERENCE_LABELS[value] }}
    </button>
  </div>
</template>
