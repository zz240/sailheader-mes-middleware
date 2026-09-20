<template>
  <div class="hc-counts-wrap">
    <!-- 页头计数条（原型 `pagehead .counts`）：**当前筛选集**的四个数，不含分页。
         还没取到时一个数都不编——空着比编一行 0 强（0 是「真的一条都没有」）。 -->
    <div v-if="items.length" class="hc-counts" aria-label="当前筛选集计数">
      <span v-for="item in items" :key="item.key">{{ item.label }} <b>{{ item.value }}</b></span>
    </div>
    <!-- 这条只读聚合挂了要说出来、也要能重试（ADR-0016 决策 11 的四态补齐口径）：
         计数条默默空着，与「真实数据是 0」在界面上长得一样。 -->
    <span v-else-if="error" class="hc-counts-err">
      计数没取到。<el-button link size="small" @click="emit('retry')">重试</el-button>
    </span>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { countItems } from '../../history/console'
import type { RunsSummary } from '../../types'

const props = defineProps<{ counts: RunsSummary | null; error?: string }>()

const emit = defineEmits<{ retry: [] }>()

const items = computed(() => countItems(props.counts))
</script>
