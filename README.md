# XLRGuiCrop

Multi-Functional Virtual Crops Plugin（多功能虚拟农作物插件）

一个 Minecraft Spigot 插件：完全虚拟的作物种植系统。玩家创建农田，作物全自动种植、生长、成熟后自动收割，产物进入多页虚拟仓库。每名玩家数据独立，存储于 SQLite。

## 技术栈

- Spigot API 26.2（MC 26.2 服务端）
- Java 25
- Maven 构建（maven-shade-plugin 打包 sqlite-jdbc）
- SQLite（org.xerial:sqlite-jdbc）

## 指令

| 指令 | 权限 | 功能 |
|------|------|------|
| `/xlr farm` | `xlr.farm` | 打开「农田」GUI |
| `/xlr crop` | `xlr.crop` | 打开「农作物仓库」GUI |
| `/xlr crop wheat` | `xlr.crop.create` | 创建小麦农田（占农田 GUI 一格） |

## 游戏流程

1. 输入 `/xlr crop wheat` 创建小麦农田
2. `/xlr farm` 打开农田 GUI（28 格农田位），点击「小麦农田」进入生长界面
3. 二级 GUI 内 54 格种植槽全自动生长，成熟自动收割
4. 收割产物（小麦 + 小麦种子）自动进入仓库
5. `/xlr crop` 打开农作物仓库，进入「小麦仓库 / 小麦种子仓库」多页 GUI 取走物品

## GitHub 编译

仓库根目录已包含完整编译配置：

- `plugin.yml`：与 `src` 同级（约定），构建时由 pom.xml 复制进 jar
- `.github/workflows/build.yml`：推送后自动用 Java 25 编译并上传产物

本地编译：

```bash
mvn -B package
```

产物：`target/XLRGuiCrop-1.0.0.jar`

## 目录结构

```
├── src/main/xlingran/com/     # 源码（package xlingran.com）
├── plugin.yml                 # 插件描述（根目录）
├── pom.xml                    # Maven 构建
├── .github/workflows/         # CI
├── docs/PLAN.md               # 规划设计文档
├── docs/HANDOVER.md           # 交接文档
└── SpigotmcApi.md             # 本地 Spigot API 文档
```

## 说明

- 当前仅实现小麦（wheat）作物，架构预留多作物扩展
- 目前参数硬编码并集中管理，后续将迁移至 config.yml
- 详见 `docs/PLAN.md`（设计）与 `docs/HANDOVER.md`（交接）
