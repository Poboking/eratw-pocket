# docs 索引

本目录只放壳层侧的公开文档与设计稿；游戏语义、编译器与引擎规格在姐妹仓
[Kotera](https://github.com/Poboking/Kotera) 的 `specs/`。

| 文档 | 内容 |
|---|---|
| [golden-runbook.md](golden-runbook.md) | 在 PC 原版 Emuera 上采集 GOLDEN 夹具控制台输出的完整流程与判据 |
| [竖屏布局设计稿.md](竖屏布局设计稿.md) | 竖屏壳的分级、布局约束、只读复用边界与验收口径 |
| [prototypes/portrait-shell-prototype.html](prototypes/portrait-shell-prototype.html) | 可交互竖屏壳原型（浏览器直接打开） |

## 相关位置

- 引擎 golden 夹具：`../Kotera/fixtures/golden/`
- 引擎语义规格：`../Kotera/specs/`（入口见 `../Kotera/specs/README.md`）
- **跨仓契约**（两仓拓扑与依赖方向、源码闸归属、CI 互检与合并次序、冻结夹具比什么、
  权威语料根是哪棵）：`../Kotera/specs/cross-repo-contract.md`
- 审壳层真源的源码哨兵：`../app/src/test/kotlin/com/eratw/port/sentinel/`
- 出包与更新工具：`../devtools/`、`../tools/apkpack/`
- 随包语料内容补丁：`../devtools/corpus-patches/`

## 维护约定

- 文档里的路径一律使用**仓库根相对路径**；跨仓引用写清 `../Kotera/` 或仓库名。
- 改动设计稿时同步检查原型与实现是否仍一致；原型只作交互参考，不是实现来源。
- golden-runbook 里引用的内部覆盖率工具不随公开仓发布，遇到时以 `specs/` 与
  `fixtures/golden/` 的现有基线为准。
- 审**壳层真源**的源码哨兵放 `app/src/test/kotlin/com/eratw/port/sentinel/`
  （主语是壳层的闸归壳层仓）；需要读引擎真源时走同级姊妹仓路径，姊妹仓不在场
  ⇒ 用 `org.opentest4j.TestAbortedException` 跳过，在场但不对 ⇒ 硬红。
