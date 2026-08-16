import { createApp } from "vue"
import { createPinia } from "pinia"
import ElementPlus from "element-plus"
import "element-plus/dist/index.css"
import App from "./App.vue"
import router from "./router"
import "./style.css"

/**
 * 应用入口：挂载 Vue 根组件，并注册 Pinia、路由与 Element Plus。
 */

const app = createApp(App)

// 注册全局状态管理
app.use(createPinia())
// 注册前端路由
app.use(router)
// 注册 Element Plus UI 组件库
app.use(ElementPlus)

// 挂载到 index.html 中的 #app 节点
app.mount("#app")
