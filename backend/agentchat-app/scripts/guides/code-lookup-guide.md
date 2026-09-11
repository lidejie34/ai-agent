# code_lookup 使用指南（外部业务仓只读代码定位）

按 `uk` 定位到本机检出的业务仓（config.json 的 `code_root`，可选 `code_include` 收窄 module），
只读，不写文件、不执行代码。所有路径经 canonical 校验锁在 code_root 内。

## 参数

| 参数 | 必填 | 说明 |
|---|---|---|
| `uk` | 是 | 应用简称/完整 uk（与 skyeye 同一套解析） |
| `mode` | 是 | `fqcn` 或 `grep` |
| `fqcn` | fqcn 必填 | 完整包名.类名，可附行号：`com.ly.xxx.OrderHandler:122`（也可分开传 `line`） |
| `line` | 否 | 1~99999 |
| `pattern` | grep 必填 | 固定字符串 ≤200 字符（异常类名、日志文案、错误码） |
| `context` | 否 | 目标行上下行数，默认 40，最大 80 |

## 代码关联优先级（不要跳步）

1. **日志堆栈里的 FQCN + 行号**——通常一步命中。直接 `mode=fqcn fqcn=...:<行号>`。
2. **异常类名**——grep `NumberFormatException`/`ServiceException` 等定位抛出点。
3. **日志文案 / 业务字段名 / 错误码常量**——grep 中文文案或字段名定位无栈日志点。

## 怎么读返回

- `matches[]`（fqcn）：可能多 module 同名同类，全列出（≤10，超出 `truncated=true`）。
  每个命中含 `repoRelativePath`、`absolutePath`、`gitHead`（非 git 仓省略）、
  `context.lines[]`（带行号的上下文）、`enclosingMethod`（向上找的最近方法签名）、`packageMatch=true`。
  包名与 FQCN 不一致的同名文件已被工具排除。
- `hits[]`（grep）：`{file, line, text}` 相对路径，≤20 条；`truncated=true` 时按 truncHint
  用更长 pattern 或换 uk（cdm 这种大仓建议先想清楚是 cdm-biz 还是 cdm-mediator）。
- `error=CODE_ROOT_UNREADABLE`：该 uk 的仓本机没检出/不可读。**非致命**：日志分析照常输出，
  在相关代码位置标 UNREADABLE，不要假装已定位。
- fqcn 0 命中：核对包名/类名大小写，或该仓是否需要 code_include 覆盖目标 module（让用户补 config）。

## 纪律

- 只读定位，基于读到的代码说话；方法语义不确定时把代码片段贴进"待确认点"，不要脑补。
- cdm 的业务逻辑在 cdm-mediator/cdm-biz，dubbo-provider 只是启动壳，别在壳里找业务分支。
- 引用代码位置时写 `相对路径:行号` + 方法名，方便用户在 IDE 里跳转。
