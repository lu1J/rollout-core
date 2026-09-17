# HTTP API

## HTTP 约定

- 写操作必须携带非空、最长 100 字符的 `X-Operator`。
- **X-Operator 仅用于审计身份传递，不是 Authentication / Authorization。** 客户端可自行填写，当前 API 不应直接暴露公网。
- Key 使用 `[a-z0-9._-]{1,100}`，不 trim、不 lowercase；名称为非空且最长 200 字符。
- 创建返回 201；读取和更新返回 200。
- 创建 Flag 可省略 variants，也可提供最多 100 个初始 Variant。
- 创建配置必须提供 enabled 和 defaultVariantKey，初始 version=0。
- 更新、enable、disable 必须提供非负整数 expectedVersion。
- 即使 enable/disable 没有改变布尔状态，成功请求仍递增 version 并记录审计。
- 审计查询必须指定 projectKey，limit 默认 50、范围 1–100，offset 默认 0。
- 时间以 UTC 生成并存储为 DATETIME(6)，JSON 中的 LocalDateTime 不带时区后缀，按 UTC 解释。

| 方法 | 路径（前缀 /api/v1） | 请求 |
| --- | --- | --- |
| POST | /projects | projectKey, name |
| GET | /projects/{projectKey} | — |
| POST | /projects/{projectKey}/environments | envKey, name |
| GET | /projects/{projectKey}/environments | — |
| POST | /projects/{projectKey}/flags | flagKey, name, valueType, variants? |
| GET | /projects/{projectKey}/flags/{flagKey} | — |
| POST | /projects/{projectKey}/flags/{flagKey}/variants | variantKey, value |
| GET | /projects/{projectKey}/flags/{flagKey}/variants | —，新增便于查看初始 Variants |
| POST | /projects/{projectKey}/environments/{envKey}/flags/{flagKey}/config | enabled, defaultVariantKey |
| GET | /projects/{projectKey}/environments/{envKey}/flags/{flagKey}/config | — |
| PUT | /projects/{projectKey}/environments/{envKey}/flags/{flagKey}/config | enabled, defaultVariantKey, expectedVersion |
| POST | /projects/{projectKey}/environments/{envKey}/flags/{flagKey}/enable | expectedVersion |
| POST | /projects/{projectKey}/environments/{envKey}/flags/{flagKey}/disable | expectedVersion |
| GET | /audits?projectKey=checkout-service&limit=50&offset=0 | — |

创建 Flag 示例：

```json
{
  "flagKey": "new-payment-flow",
  "name": "New payment flow",
  "valueType": "BOOLEAN",
  "variants": [
    {"variantKey": "old", "value": false},
    {"variantKey": "new", "value": true}
  ]
}
```

创建配置：`{"enabled":false,"defaultVariantKey":"old"}`。
启用：`{"expectedVersion":0}` → version=1。
禁用：`{"expectedVersion":1}` → version=2。
再次使用 expectedVersion=0 → 409，失败写操作不会新增审计。

| HTTP | code | 含义 |
| --- | --- | --- |
| 400 | validation_error | 参数、类型、Key、Header 校验失败 |
| 404 | resource_not_found | 资源不存在或不属于路径指定的项目/Flag |
| 409 | duplicate_resource | 数据库唯一约束冲突 |
| 409 | optimistic_lock_conflict | 版本过期或并发条件更新影响 0 行 |
| 500 | internal_error | 未预期服务端错误，响应不包含 SQL 或堆栈 |

错误采用 Spring ProblemDetail（`application/problem+json`），包含稳定 `code`。
Variant 响应中的 value 是 JSON 节点；审计响应的 before / after 是解析后的 JSON 快照，创建操作 before=null。


## Evaluation

`POST /api/v1/evaluate` 无需 X-Operator（只读、不写 Audit）：

```json
{
  "projectKey": "checkout-service",
  "environmentKey": "prod",
  "flagKey": "new-payment-flow",
  "context": {"userId": "user-123", "country": "JP", "vipLevel": 5, "appVersion": "2.3.1", "attributes": {"plan": "pro"}}
}
```

响应包含 `flagKey / variantKey / value / reason / configVersion / matchedRulePriority / bucket`。
value 保留 BOOLEAN、STRING、NUMBER、JSON 类型。规则命中返回 priority；仅百分比分流返回 bucket，其余情况对应解释字段为 null。
求值顺序：关闭 → defaultVariant/DISABLED；规则首个命中 → RULE_MATCH；无命中且有 rollout → PERCENTAGE_ROLLOUT；否则 → defaultVariant/DEFAULT。

`PUT /api/v1/projects/{projectKey}/environments/{envKey}/flags/{flagKey}/evaluation-policy` 必须带 X-Operator：

```json
{
  "expectedVersion": 0,
  "rules": [{"priority": 10, "match": "ALL", "conditions": [
    {"attribute": "country", "operator": "EQ", "value": "JP"},
    {"attribute": "vipLevel", "operator": "GTE", "value": 3}
  ], "variantKey": "new"}],
  "rollout": [{"variantKey": "new", "weight": 1000}, {"variantKey": "old", "weight": 9000}]
}
```

成功返回 `{"version":1,"policy":{...}}`。这是完整策略替换；`{"expectedVersion":1}` 清空规则和 rollout。
rules 缺省/null/空数组表示无规则；rollout 缺省/null 表示不分流，空数组不合法。存在 rollout 时整数权重之和必须为 10000。
priority 非负且不重复，越小越优先；ALL=AND，ANY=OR，first-match-wins。
支持 EQ/NEQ/IN/NOT_IN/GT/GTE/LT/LTE/CONTAINS；缺失字段或类型不兼容均不匹配，负向操作符也不例外。
内置字段优先；appVersion 仅精确 EQ/NEQ/IN/NOT_IN；无 SemVer range。userId 必填、最长 256 字符、禁止 NUL。
上限：100 条 Rule、每条 1–20 条 Condition、100 个 allocation、IN/NOT_IN 1–100 个同类型标量。
同一项目/环境/Flag/userId 使用永久固定的 UTF-8 + NUL 分隔 + SHA-256 前四字节大端无符号值 %10000，得到 0–9999。

V2 新增 `evaluation_policy_json JSON NULL`，未修改 V1。policy 与原有开关/默认值共用 config version 乐观锁，stale 返回 409 `optimistic_lock_conflict`，成功更新和 `EVALUATION_POLICY_UPDATED` 审计同事务。

```powershell
mvn -pl rolloutcore-server -am test
mvn clean verify
# 启动连接真实 MySQL 的新 JAR 后执行；默认生成唯一 ProjectKey，不清理数据。
powershell -ExecutionPolicy Bypass -File scripts/evaluation-e2e.ps1
```
