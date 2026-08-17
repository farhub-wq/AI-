<script setup lang="ts">
import { reactive, ref, watch } from "vue"
import { useRouter } from "vue-router"
import { ElMessage } from "element-plus"
import { useAuthStore } from "@/stores/auth"

/**
 * 登录/注册页：
 * - 登录：常见后缀邮箱 或 11 位手机号 + 密码
 * - 注册：邮箱注册 / 手机号注册 分开；邮箱仅允许 qq/163/gmail 等常见后缀
 * - 注册成功后切回登录 Tab，不自动登录、不写 token
 */

const authStore = useAuthStore()
const router = useRouter()
/** 顶部 Tab：login | register */
const activeTab = ref("login")
/** 注册子模式：EMAIL 邮箱注册 / PHONE 手机号注册（互斥表单） */
const registerMode = ref<"EMAIL" | "PHONE">("EMAIL")

/**
 * 允许注册与邮箱登录的常见邮箱后缀（与后端 AuthService 白名单保持一致）。
 * 用户要求限制 @qq.com、@163.com、@gmail.com 等常见后缀。
 */
const ALLOWED_EMAIL_DOMAINS = [
  "qq.com",
  "163.com",
  "126.com",
  "gmail.com",
  "foxmail.com",
  "outlook.com",
  "hotmail.com",
  "sina.com",
  "yeah.net"
] as const

const EMAIL_HINT = "@qq.com / @163.com / @gmail.com 等"

const loginForm = reactive({
  // 演示账号：常见后缀邮箱（库未重建时也可用手机号 13800138000）
  account: "demo@qq.com",
  password: "Passw0rd!"
})

/** 邮箱注册表单（不含手机号字段，避免混填） */
const emailRegisterForm = reactive({
  displayName: "",
  email: "",
  password: ""
})

/** 手机号注册表单（不含邮箱字段，避免混填） */
const phoneRegisterForm = reactive({
  displayName: "",
  phone: "",
  password: ""
})

/** 手机输入框：实时剥除非数字并截断到 11 位 */
watch(
  () => phoneRegisterForm.phone,
  (value) => {
    const digits = value.replace(/\D/g, "").slice(0, 11)
    if (digits !== value) {
      phoneRegisterForm.phone = digits
    }
  }
)

/** 校验是否为允许的常见邮箱后缀 */
function isAllowedEmail(email: string): boolean {
  const normalized = email.trim().toLowerCase()
  if (!/^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/.test(normalized)) {
    return false
  }
  const domain = normalized.slice(normalized.lastIndexOf("@") + 1)
  return (ALLOWED_EMAIL_DOMAINS as readonly string[]).includes(domain)
}

/** 提交登录：成功后进入对话页（或 redirect 回原先想访问的功能页） */
async function submitLogin() {
  const account = loginForm.account.trim()
  // 邮箱形态账号：前端先拦非白名单后缀，减少无效请求
  if (account.includes("@") && !isAllowedEmail(account)) {
    ElMessage.warning(`邮箱登录仅支持常见后缀：${EMAIL_HINT}`)
    return
  }
  try {
    await authStore.login(account, loginForm.password)
    ElMessage.success("登录成功")
    // 支持未登录访问功能页被拦后，登录完成回到原目标
    const redirect = typeof router.currentRoute.value.query.redirect === "string"
      ? router.currentRoute.value.query.redirect
      : "/chat"
    const safe = redirect.startsWith("/") && !redirect.startsWith("//") ? redirect : "/chat"
    router.replace(safe)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "登录失败")
  }
}

/** 邮箱注册：仅昵称+邮箱+密码；昵称/邮箱唯一；成功或幂等命中后切回登录，不进系统 */
async function submitEmailRegister() {
  if (!emailRegisterForm.displayName.trim()) {
    ElMessage.warning("请填写昵称")
    return
  }
  if (!isAllowedEmail(emailRegisterForm.email)) {
    ElMessage.warning(`请使用常见邮箱后缀注册：${EMAIL_HINT}`)
    return
  }
  if (emailRegisterForm.password.length < 8) {
    ElMessage.warning("密码至少 8 位")
    return
  }
  try {
    const result = await authStore.register({
      registerType: "EMAIL",
      displayName: emailRegisterForm.displayName.trim(),
      email: emailRegisterForm.email.trim(),
      password: emailRegisterForm.password
    })
    // 首次注册与幂等重复提交均不写 token；引导用户自行登录
    ElMessage.success(result.message || "已注册，请返回登录页登录")
    activeTab.value = "login"
    loginForm.account = emailRegisterForm.email.trim()
    loginForm.password = ""
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "注册失败")
  }
}

/** 手机号注册：仅昵称+手机+密码；昵称/手机唯一；成功或幂等命中后切回登录，不进系统 */
async function submitPhoneRegister() {
  if (!phoneRegisterForm.displayName.trim()) {
    ElMessage.warning("请填写昵称")
    return
  }
  // 与后端一致：1 开头共 11 位
  if (!/^1\d{10}$/.test(phoneRegisterForm.phone)) {
    ElMessage.warning("手机号须为 1 开头的 11 位数字")
    return
  }
  if (phoneRegisterForm.password.length < 8) {
    ElMessage.warning("密码至少 8 位")
    return
  }
  try {
    const result = await authStore.register({
      registerType: "PHONE",
      displayName: phoneRegisterForm.displayName.trim(),
      phone: phoneRegisterForm.phone,
      password: phoneRegisterForm.password
    })
    ElMessage.success(result.message || "已注册，请返回登录页登录")
    activeTab.value = "login"
    loginForm.account = phoneRegisterForm.phone
    loginForm.password = ""
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "注册失败")
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-card glass-panel">
      <div class="hero-copy">
        <h1 class="brand-title">智惠客服平台</h1>
      </div>

      <el-tabs v-model="activeTab">
        <!-- 登录：邮箱（常见后缀）或手机号 -->
        <el-tab-pane label="登录" name="login">
          <el-form label-position="top" @submit.prevent>
            <el-form-item label="账号">
              <el-input
                v-model="loginForm.account"
                placeholder="常见邮箱或手机号，例如 demo@qq.com"
              />
            </el-form-item>
            <el-form-item label="密码">
              <el-input v-model="loginForm.password" type="password" show-password />
            </el-form-item>
            <el-button type="primary" :loading="authStore.loading" class="login-button" @click="submitLogin">
              登录
            </el-button>
          </el-form>
        </el-tab-pane>

        <!-- 注册：子 Tab 拆成邮箱 / 手机，禁止混填 -->
        <el-tab-pane label="注册" name="register">
          <el-radio-group v-model="registerMode" class="register-mode">
            <el-radio-button label="EMAIL">邮箱注册</el-radio-button>
            <el-radio-button label="PHONE">手机号注册</el-radio-button>
          </el-radio-group>

          <el-form v-if="registerMode === 'EMAIL'" label-position="top" @submit.prevent>
            <el-form-item label="昵称">
              <el-input v-model="emailRegisterForm.displayName" placeholder="希望系统如何称呼你" />
            </el-form-item>
            <el-form-item label="邮箱">
              <el-input
                v-model="emailRegisterForm.email"
                :placeholder="`仅支持 ${EMAIL_HINT}`"
              />
            </el-form-item>
            <el-form-item label="密码">
              <el-input v-model="emailRegisterForm.password" type="password" show-password placeholder="至少 8 位" />
            </el-form-item>
            <el-button type="primary" :loading="authStore.loading" class="login-button" @click="submitEmailRegister">
              邮箱注册
            </el-button>
          </el-form>

          <el-form v-else label-position="top" @submit.prevent>
            <el-form-item label="昵称">
              <el-input v-model="phoneRegisterForm.displayName" placeholder="希望系统如何称呼你" />
            </el-form-item>
            <el-form-item label="手机号">
              <el-input
                v-model="phoneRegisterForm.phone"
                maxlength="11"
                inputmode="numeric"
                placeholder="1 开头共 11 位"
              />
            </el-form-item>
            <el-form-item label="密码">
              <el-input v-model="phoneRegisterForm.password" type="password" show-password placeholder="至少 8 位" />
            </el-form-item>
            <el-button type="primary" :loading="authStore.loading" class="login-button" @click="submitPhoneRegister">
              手机号注册
            </el-button>
          </el-form>
        </el-tab-pane>
      </el-tabs>

      <div class="hint-card">
        <strong>演示账号</strong>
        <span class="mono">demo@qq.com / Passw0rd!</span>
        <span class="mono">或手机号 13800138000</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: 24px;
}

.login-card {
  width: min(760px, 100%);
  padding: 36px;
  display: grid;
  gap: 28px;
}

.hero-copy {
  text-align: center;
}

.brand-title {
  margin: 0;
  font-size: 48px;
  line-height: 1.2;
  font-weight: 700;
  letter-spacing: 0.04em;
  color: #1f2a44;
}

/* 注册方式切换：邮箱 / 手机各占一半宽度 */
.register-mode {
  margin-bottom: 16px;
  width: 100%;
  display: flex;
}

.register-mode :deep(.el-radio-button) {
  flex: 1;
}

.register-mode :deep(.el-radio-button__inner) {
  width: 100%;
}

.login-button {
  width: 100%;
  height: 46px;
}

.hint-card {
  padding: 18px;
  border-radius: 18px;
  background: linear-gradient(135deg, rgba(255, 234, 206, 0.72), rgba(255, 255, 255, 0.8));
  display: flex;
  flex-direction: column;
  gap: 8px;
}
</style>
