# Evaluation contract

## Evaluation precedence

1. `enabled=false`：直接选择 defaultVariant，`DISABLED`；不执行规则或 Hash。
2. 已启用：按 priority 升序查找第一个匹配 Rule，返回 `RULE_MATCH` 和 matchedRulePriority。
3. 没有 Rule 命中且存在 rollout：按 bucket 落入累计权重区间选择，返回 `PERCENTAGE_ROLLOUT` 和 bucket。
4. 无规则命中且没有 rollout：defaultVariant，`DEFAULT`。

默认 Variant 也可为 true、字符串、数字、对象或数组，禁用不强制返回 false。
除对应 reason 的解释字段外，matchedRulePriority/bucket 为 null。所有响应携带读取快照的 configVersion。
不返回 SQL、内部实体 id、全部策略或用户上下文；求值不产生写入审计。

## Stable Hash 精确算法契约

输入原样连接（不 trim、不 lowercase、不做 Unicode normalization）：

```text
projectKey + U+0000 + environmentKey + U+0000 + flagKey + U+0000 + userId
```

以 UTF-8 编码，计算 JDK SHA-256。取 digest[0..3]，按 **big-endian** 解读为 unsigned 32-bit：
`u = b0×2^24 + b1×2^16 + b2×2^8 + b3`，每个字节取 0..255。
`bucket = u % 10000`，结果为 0..9999。ByteBuffer 默认大端，Integer.toUnsignedLong 避免符号问题。
userId 必填、非空白、最长 256 字符、禁止 NUL；项目/环境/Flag 沿用 Key 约束，因此分隔无歧义。
不包含 configVersion，不使用 Random、Object.hashCode 或进程状态。

固定向量由独立 .NET SHA256 计算，再写入 Java Golden Tests：

| project | environment | flag | userId | bucket |
| --- | --- | --- | --- | --- |
| shop | prod | pay | user-123 | 2750 |
| checkout-service | prod | new-payment-flow | user-123 | 4086 |
| shop | test | pay | 用户-一 | 48 |

固定样本 `shop/prod/pay/user-000001` 至 `user-050000`，bucket<1000 命中 **5061/50000 = 10.122%**，容差 9%–11%。
测试无随机样本，同时检查范围与重复输入；Service 测试重复求值并比较完整响应。
区间按 rollout 数组顺序累计，采用 `[lower, upper)`；0 权重不命中，10000 为 100%。
这是永久 v1 分桶契约。将来改变编码、分隔、字节序、Hash 或取模方式必须显式迁移，不能静默重分桶。
调整权重/数组顺序会改变部分 bucket 的 Variant，但同一标识的 bucket 不变。

## Rule Engine semantics

安全的结构化 JSON DSL，不执行表达式、脚本、反射路径或任意代码。
ALL=AND，ANY=OR；priority 非负且不重复，越小越先执行，first-match-wins，不依赖输入 Rule 数组顺序。

| Operator | 语义 |
| --- | --- |
| EQ / NEQ | 合法 JSON 标量同类型比较；数字 BigDecimal.compareTo，因此 5 与 5.0 相等 |
| IN / NOT_IN | rule value 为 1–100 个同类型标量数组；不做类型转换 |
| GT / GTE / LT / LTE | 仅数字比较，不对字符串排序 |
| CONTAINS | 字符串字面子串，大小写敏感 |

缺失属性和运行时类型不兼容统一不匹配，NEQ/NOT_IN 也返回 false，不产生 ClassCastException/500。
attributes 中显式 JSON null 可以 EQ null；缺失与 null 区分。内置字段 null 视为缺失。
userId/country/appVersion 为字符串，vipLevel 为数字；内置名称优先且保留，即使缺失也不会从 attributes 补值。
扩展属性键按字面匹配，允许点号但不遍历嵌套对象。对象/数组运行时值不参与标量比较。
appVersion 仅 EQ/NEQ/IN/NOT_IN 精确匹配，未实现 SemVer range。

写入限制：最多 100 Rule、每条 1–20 Condition、最多 100 Allocation；priority 和 rollout variantKey 不重复。
Condition attribute 为 `[a-zA-Z][a-zA-Z0-9_.-]{0,99}`，字符串 rule value 最长 1024；IN 数组 1–100 个同类型标量。
weight 为 0..10000 整数且总和恰好 10000；Rule/rollout 的 Variant 必须属于当前 Flag。
context country/appVersion 最长 256，attributes 最多 100 个 key。

## API

- `POST /api/v1/evaluate`：请求 projectKey/environmentKey/flagKey/context；无 X-Operator 要求。
- `PUT /api/v1/projects/{projectKey}/environments/{envKey}/flags/{flagKey}/evaluation-policy`：expectedVersion/rules/rollout，必填 X-Operator。
- policy 成功返回 `{"version":n,"policy":{"rules":...,"rollout":...}}`。
- evaluation 返回 flagKey/variantKey/value/reason/configVersion/matchedRulePriority/bucket。
- 400 validation_error；404 resource_not_found；409 optimistic_lock_conflict，沿用 ProblemDetail。
- rules 缺省/null/[] 表示无规则；rollout 缺省/null 表示无 rollout，[] 不合法。
- policy PUT 是完整替换，不做局部合并；仅传 expectedVersion 可清空策略，仍递增版本并审计。

完整请求示例见 [API](API.md)。GET Config 保持实体的 evaluationPolicyJson 字符串形式，policy PUT 响应和 Audit 中为解析后的 JSON 对象。
