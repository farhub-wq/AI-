<script setup lang="ts">
import { reactive, ref } from "vue"
import { useRouter } from "vue-router"
import { ElMessage } from "element-plus"
import { useAuthStore } from "@/stores/auth"

/**
 * 登录/注册页：提交凭据后写入认证态，成功则进入对话页。
 */

const authStore = useAuthStore()
const router = useRouter()
const activeTab = ref("login")

const loginForm = reactive({
  account: "demo@example.com",
  password: "Passw0rd!"
})

const registerForm = reactive({
  displayName: "",
  email: "",
  phone: "",
  password: ""
})

/** 提交登录表单，成功后跳转对话页 */
async function submitLogin() {
  try {
    await authStore.login(loginForm.account, loginForm.password)
    ElMessage.success("登录成功")
    router.push("/chat")
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "登录失败")
  }
}

/** 提交注册表单，成功后自动登录并进入对话页 */
async function submitRegister() {
  try {
    await authStore.register({
      displayName: registerForm.displayName,
      email: registerForm.email,
      phone: registerForm.phone || undefined,
      password: registerForm.password
    })
    ElMessage.success("注册成功，已自动登录")
    router.push("/chat")
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
        <el-tab-pane label="登录" name="login">
          <el-form label-position="top" @submit.prevent>
            <el-form-item label="账号">
              <el-input v-model="loginForm.account" placeholder="邮箱或手机号，例如 demo@example.com" />
            </el-form-item>
            <el-form-item label="密码">
              <el-input v-model="loginForm.password" type="password" show-password />
            </el-form-item>
            <el-button type="primary" :loading="authStore.loading" class="login-button" @click="submitLogin">
              登录
            </el-button>
          </el-form>
        </el-tab-pane>

        <el-tab-pane label="注册" name="register">
          <el-form label-position="top" @submit.prevent>
            <el-form-item label="昵称">
              <el-input v-model="registerForm.displayName" placeholder="希望系统如何称呼你" />
            </el-form-item>
            <el-form-item label="邮箱">
              <el-input v-model="registerForm.email" placeholder="name@example.com" />
            </el-form-item>
            <el-form-item label="手机号">
              <el-input v-model="registerForm.phone" placeholder="选填" />
            </el-form-item>
            <el-form-item label="密码">
              <el-input v-model="registerForm.password" type="password" show-password />
            </el-form-item>
            <el-button type="primary" :loading="authStore.loading" class="login-button" @click="submitRegister">
              创建账号
            </el-button>
          </el-form>
        </el-tab-pane>
      </el-tabs>

      <div class="hint-card">
        <strong>演示账号</strong>
        <span class="mono">demo@example.com / Passw0rd!</span>
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
