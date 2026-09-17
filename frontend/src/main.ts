import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
// 设计令牌（ADR-0013 第 1 期）：必须排在 element-plus/dist/index.css **之后**——CSS 同特异性看先后，
// 晚了才压得住组件库的默认值。别再往后挪。见 ./styles/tokens.css 的文件头。
import './styles/tokens.css'
import App from './App.vue'
import router from './router'

createApp(App).use(ElementPlus).use(router).mount('#app')
