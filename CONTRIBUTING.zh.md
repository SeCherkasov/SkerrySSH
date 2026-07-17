# 贡献指南

感谢考虑为 Skerry 贡献代码！

## 开发环境

- **JDK 21+**
- **Android Studio** (可选，用于 Android 目标)
- **Git**

### 克隆仓库

```bash
git clone https://github.com/onepve/SkerrySSH
cd SkerrySSH
```

## 项目结构

```
SkerrySSH/
├── composeApp/              # Kotlin Multiplatform 客户端
│   └── src/
│       ├── commonMain/      # 共享业务代码
│       ├── desktopMain/     # 桌面端 (Compose Desktop)
│       └── androidMain/     # Android 端
├── server/                  # Ktor 同步服务器
│   └── src/main/kotlin/
├── sync-wire/               # 客户端 ⇆ 服务端共享 DTO
│   └── src/main/kotlin/
├── docs/                    # 文档
├── data/                    # Docker 数据持久化目录
└── docker-compose.yml       # Docker 服务编排
```

## 构建

```bash
# 全部模块
./gradlew build

# 仅客户端
./gradlew :composeApp:build

# 仅服务端
./gradlew :server:build
```

桌面端可运行包：

```bash
./gradlew :composeApp:createDistributable
```

## 测试

```bash
# 全部测试
./gradlew test

# 仅服务端测试
./gradlew :server:test
```

## 代码风格

- Kotlin 代码采用官方风格指南
- 资源文件 (values-zh, values-ru) 键名与 values/ 目录完全对齐
- UI 组件使用项目自定义的 `D` 颜色系统和 `Txt`/`Sym` 组件库

## 国际化 (i18n)

添加新语言翻译：

1. 在 `composeApp/src/commonMain/composeResources/` 下创建 `values-<语言代码>/` 目录
2. 确保所有 `strings_*.xml` 文件键集与 `values/` 目录完全对齐（可使用 `diff-keys` 脚本检查）
3. 在 `values/arrays.xml` 的 `language_entries` 和 `language_values` 数组中注册新语言
4. 在 UI 中的语言切换按钮组中加入新选项

## 提交规范

- 一条提交一件事
- 使用中文或英文均可，描述清晰
- 功能分支命名：`feature/xxx`，修复分支：`fix/xxx`

## CI/CD

- PR 推送自动运行 `./gradlew build` 检查编译和测试
- Tag 推送 (`vX.Y.Z`) 触发多平台客户端构建和 Release 发布
- Release 默认为草稿状态，需人工审核后发布

## 联系方式

- Issue: [GitHub Issues](https://github.com/onepve/SkerrySSH/issues)
- 讨论: [GitHub Discussions](https://github.com/onepve/SkerrySSH/discussions)
