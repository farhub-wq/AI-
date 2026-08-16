/// <reference types="vite/client" />

/**
 * Vite 客户端类型声明：为 import.meta.env 与 `.vue` 模块导入提供类型支持。
 */

declare module "*.vue" {
  import type { DefineComponent } from "vue"
  const component: DefineComponent<object, object, unknown>
  export default component
}
