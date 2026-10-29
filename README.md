# 校园循环 · 实现阶段独立协作包

本包用于将已完成的需求分析交接到四人实现阶段。将整个目录解压或复制给组员即可；不需要访问原电脑的 C/D 盘目录。所有当前协作说明均使用包内相对路径。

## 先从这里读

1. [共同协作约定](skills/协作约定.md)：范围、角色边界、接口协作及问题闭环。
2. 本人角色 skill：
   - [A · 服务器、公网、DNS、挂载与恢复](skills/campus-a-deploy/SKILL.md)
   - [B · 后端、MySQL 替换与接口协议](skills/campus-b-backend-mysql/SKILL.md)
   - [C · Vue 前端与完整操作体验](skills/campus-c-frontend/SKILL.md)
   - [D · 关键业务审查、冲突检测与独立验收](skills/campus-d-business-review/SKILL.md)
3. [正式需求正文](requirements/documents/软件工程课程需求报告.md)：FR/UC/BR/ST 和验收标准的依据。
4. [参考前后端说明](reference-app/README.md)：实际代码、启动方式和已知边界。

## 目录与路径口径

```text
本包/
├─ README.md                       本入口
├─ skills/                         当前实现阶段协作约定与 ABCD skill
├─ reference-app/                  已完成的参考前后端，可作为后续开发起点
│  ├─ frontend/                    Vue 组件、HTML/CSS、请求封装及锁定依赖
│  ├─ backend/                     Java 后端、SQL、配置与集成测试
│  │  └─ target/campus-market-1.0.0.jar  已构建可执行版本
│  ├─ Dockerfile、compose.yml       容器构建和 MySQL 部署配置
│  └─ 启动项目.cmd、build.ps1 等     本地启动和源码构建
├─ requirements/                   原正式需求提交包，完整保留相对引用
│  ├─ documents/                   三份正式报告的 MD、DOCX、PDF
│  ├─ attachments/                 业务规则、模型、旧原型、验证及证据
│  └─ initialization/              需求阶段历史约定，非本阶段分工
├─ evidence/                       参考实现已有的测试结果与页面截图
└─ 文件清单.json                   包内文件大小与 SHA-256
```

角色 skill 内的 `backend/`、`frontend/`、`docs/` 和脚本路径相对 `reference-app/`。例如 B 后续维护的接口契约放在 `reference-app/docs/api/`，D 的台账放在 `reference-app/docs/verification/`。Markdown 链接相对各自所在文件。

## 各角色需要哪些旧材料

| 角色 | 首读内容 | 需要时再读 |
| --- | --- | --- |
| A | 需求正文 1.4—1.5、2.6、第6章、8.3；参考项目的 Dockerfile、Compose 和配置 | 质量报告，数据与隐私边界 |
| B | 需求正文第2—5章、7.1；业务规则和模型说明 | 数据字典、图源、场景与历史反例 |
| C | 需求正文第2章交互、4章权限、5章状态；当前 Vue 代码 | 旧原型页面、场景操作及页面验证记录 |
| D | 需求正文 FR/UC/BR/ST、7.1—7.4；质量验证报告 | 场景数据、独立反例、问题复核和总证据索引 |

常用入口：

- [正式提交包说明](requirements/README.md)
- [业务规则与来源](requirements/attachments/requirements/业务规则与来源.md)
- [模型解释与状态转换](requirements/attachments/requirements/模型解释与状态转换.md)
- [场景数据与走查脚本](requirements/attachments/verification/场景数据与走查脚本.md)
- [质量验证报告](requirements/documents/需求分析质量验证报告.md)
- [AI智能体使用验证报告](requirements/documents/AI智能体使用验证报告.md)
- [总证据索引](requirements/attachments/evidence/证据索引.md)
- [旧需求原型说明](requirements/attachments/prototype/README.md)

完整保留约 10MB 的正式需求包，是为了让报告中的图源、验证、证据与格式附件仍可查阅。旧材料中的作者分工、历史时间和机器路径属于历史记录，不作为当前环境配置，也不要求逐份执行旧角色 skill。当前分工只看本包 `skills/`。

## 试运行参考前后端

需要 Java 17 或更高版本。设置 `JAVA_HOME` 或将 Java 加入 PATH，进入 `reference-app/` 双击 `启动项目.cmd`；已有 JAR，无须为首次试运行安装 Node/Maven。也可在 `reference-app/backend/` 执行：

```sh
java -Dfile.encoding=UTF-8 -jar target/campus-market-1.0.0.jar
```

访问 `http://127.0.0.1:18080/`。首次启动在当前后端工作目录创建文件数据库与上传目录，不依赖原电脑的演示数据库。

本地演示账号为 `buyer`、`buyer2`、`seller`、`admin`，演示密码均为 `Campus123!`。这些账号仅供本地检查；A/B 负责在公网交付前关闭演示种子并提供正式管理员初始化。

修改源码后的构建需要 JDK 17、Maven 和 Node.js 20.19+，Windows 在 `reference-app/` 执行 `build.ps1`。其他系统按 `reference-app/README.md` 或 Dockerfile 构建。包内启动/构建脚本已去除原电脑固定的 Java、Maven 和 Codex 运行时路径。

默认仍为 H2；MySQL 8.4 配置已随代码提供，B 负责切换并在真实 MySQL 验证，A 负责目标服务器部署。当前 JAR 与历史 H2 测试不代表 MySQL、公网、并发容量或恢复验收已通过。

## 发给智能体的任务入口

从本包根目录启动，按角色替换下面的角色字母与 skill 名称即可：

> 你负责本项目实现阶段的 B 角色。先读 `skills/协作约定.md`、`skills/campus-b-backend-mysql/SKILL.md` 和本包 README，再读取其中指定的正式需求及代码。实现项目在 `reference-app/`，正式需求在 `requirements/`。先核对实际输入和未完成项，按角色边界开展工作；不要把旧需求阶段的 ABCD 分工套用到本阶段。

参考实现是本次打包时的代码快照，不带原仓库历史；成员后续应明确共同仓库及版本，不能将参考版本的全部实现记为个人后续贡献。
