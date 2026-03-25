# paas-privilege-service 技术设计方案

## 1. 服务概述

权限服务，负责 aPaaS 平台的全量权限体系。包含功能权限（角色/权限集）和数据权限（共享规则/岗位/团队/地域）两大体系。其他服务通过 RPC 调用本服务进行权限校验和数据权限过滤条件获取。

---

## 2. 系统架构

```mermaid
graph TB
    subgraph 入口层
        REST[REST API<br>/api/v1/privilege]
        RPC[RPC Provider<br>OpenFeign]
    end

    subgraph 核心业务层
        RoleSvc[RoleService<br>角色/权限集]
        PositionSvc[PositionService<br>岗位树]
        TeamSvc[TeamMemberService<br>团队成员]
        ShareSvc[ShareRuleService<br>共享规则]
        TerritorySvc[TerritoryService<br>地域权限]
        DenormSvc[DenormalizeService<br>权限去规范化计算]
        CheckSvc[PermissionCheckService<br>权限校验]
    end

    subgraph 基础设施层
        DB[(MySQL/PostgreSQL)]
        Redis[Redis<br>权限缓存/分布式锁]
        MQ[RocketMQ<br>权限变更异步计算]
        Nacos[Nacos]
    end

    subgraph 外部依赖
        MetaRepo[paas-metarepo-service<br>对象权限配置]
    end

    REST --> RoleSvc
    REST --> PositionSvc
    REST --> ShareSvc
    RPC --> CheckSvc
    RPC --> DenormSvc

    RoleSvc --> DB
    PositionSvc --> DB
    TeamSvc --> DB
    ShareSvc --> DB
    TerritorySvc --> DB
    DenormSvc --> DB
    DenormSvc --> MQ
    CheckSvc --> Redis

    DenormSvc --> MetaRepo
```

---

## 3. 权限体系设计

### 3.1 功能权限体系

```mermaid
graph LR
    User[用户] --> Role[角色]
    Role --> PermSet[权限集]
    PermSet --> ObjPerm[对象权限<br>增删改查]
    PermSet --> FieldPerm[字段权限<br>读/写/隐藏]
    PermSet --> AppPerm[应用权限<br>菜单/功能]
```

**对象权限级别（ObjectAccess）：**

| 级别 | 说明 |
|---|---|
| NONE | 无权限 |
| READ | 只读（受数据权限约束） |
| CREATE | 可创建 |
| EDIT | 可编辑（受数据权限约束） |
| DELETE | 可删除（受数据权限约束） |
| ALL | 全部权限 |

### 3.2 数据权限体系

数据权限决定用户能看到哪些记录，通过以下维度叠加计算：

```mermaid
graph TB
    DataPerm[数据权限] --> OrgHier[组织层级<br>岗位树]
    DataPerm --> TeamMember[团队成员<br>直接共享]
    DataPerm --> ShareRule[共享规则<br>条件共享]
    DataPerm --> Territory[地域权限<br>区域划分]
    DataPerm --> OwnerRule[所有者规则<br>自己的记录]
```

**数据权限计算结果（去规范化）：**
- 将用户能访问的所有记录 ID 预计算并存储到 `priv_subject_share_data` 表
- 查询时直接 JOIN 该表，避免实时计算
- 权限变更时异步重新计算（MQ 驱动）

---

## 4. 模块职责

### 4.1 RoleService（角色/权限集）

| 方法 | 说明 |
|---|---|
| `createRole` | 创建角色，关联权限集 |
| `assignRole` | 为用户分配角色 |
| `getObjectPermission` | 查询用户对某对象的功能权限 |
| `getFieldPermission` | 查询用户对某字段的权限 |
| `checkPermission` | 校验用户是否有某操作权限 |

### 4.2 PositionService（岗位树）

管理企业组织架构的岗位层级，用于数据权限的上下级可见性控制。

```mermaid
graph TD
    CEO[CEO岗位] --> VP1[VP销售]
    CEO --> VP2[VP市场]
    VP1 --> MGR1[销售经理-华东]
    VP1 --> MGR2[销售经理-华南]
    MGR1 --> REP1[销售代表A]
    MGR1 --> REP2[销售代表B]
```

**岗位数据权限规则：**
- `PRIVATE`：只能看自己的记录
- `PUBLIC_READ`：可以看下级的记录（只读）
- `PUBLIC_READ_WRITE`：可以看并编辑下级的记录
- `PUBLIC_FULL`：可以看并完全控制下级的记录

### 4.3 TeamMemberService（团队成员）

支持将记录直接共享给特定用户或用户组。

- 记录级别的精细化共享
- 支持共享给用户、角色、公共组
- 共享权限可设置为只读或读写

### 4.4 ShareRuleService（共享规则）

基于条件的自动共享规则，满足条件的记录自动共享给指定用户群体。

```mermaid
flowchart TD
    A[定义共享规则] --> B[设置触发条件<br>如 Status=Active]
    B --> C[设置共享目标<br>如 角色=销售经理]
    C --> D[设置共享权限<br>只读/读写]
    D --> E[规则生效]
    E --> F[数据变更时<br>异步重新计算]
```

### 4.5 DenormalizeService（权限去规范化计算）

核心计算引擎，将复杂的权限规则预计算为扁平的数据权限表。

**计算触发时机：**
- 用户角色变更
- 岗位层级变更
- 共享规则变更
- 团队成员变更
- 记录所有者变更

**计算流程：**
```mermaid
flowchart TD
    A[接收权限变更 MQ 事件] --> B[确定影响范围<br>哪些用户/记录受影响]
    B --> C[分批次加入计算队列]
    C --> D[并行计算每个用户的数据权限]
    D --> E[合并岗位/团队/共享规则/地域权限]
    E --> F[写入 priv_subject_share_data]
    F --> G[更新计算版本号]
    G --> H[通知缓存失效]
```

### 4.6 PermissionCheckService（权限校验）

提供给其他服务调用的权限校验接口。

- 功能权限校验：用户是否有某对象的某操作权限
- 数据权限过滤：返回 SQL WHERE 条件，供 entity-service 拼接查询

---

## 5. 数据模型

```mermaid
classDiagram
    class Role {
        +String id
        +String tenantId
        +String name
        +String description
    }

    class PermissionSet {
        +String id
        +String tenantId
        +String roleId
        +String objectApiKey
        +String accessLevel
        +Boolean canCreate
        +Boolean canRead
        +Boolean canEdit
        +Boolean canDelete
    }

    class PositionTree {
        +String id
        +String tenantId
        +String parentId
        +String name
        +String dataAccessLevel
        +Integer level
        +String path
    }

    class PrivSubjectShareData {
        +String id
        +String tenantId
        +String userId
        +String objectApiKey
        +String recordId
        +String accessLevel
        +String sourceType
        +Long version
    }

    class ShareRule {
        +String id
        +String tenantId
        +String objectApiKey
        +String conditionJson
        +String targetType
        +String targetId
        +String accessLevel
        +Boolean isActive
    }

    Role "1" --> "N" PermissionSet
    PositionTree "1" --> "N" PositionTree : children
    ShareRule "1" --> "N" PrivSubjectShareData : generates
```

---

## 6. 核心流程

### 6.1 数据权限过滤流程

```mermaid
sequenceDiagram
    participant EntitySvc
    participant PrivRPC
    participant CheckService
    participant Redis
    participant DB

    EntitySvc->>PrivRPC: getDataPermissionFilter(userId, objectApiKey)
    PrivRPC->>CheckService: buildFilter(userId, objectApiKey)
    CheckService->>Redis: GET priv:filter:{userId}:{objectApiKey}
    alt 缓存命中
        Redis-->>CheckService: SQL 过滤条件
    else 缓存未命中
        CheckService->>DB: 查询 priv_subject_share_data
        CheckService->>CheckService: 构建 SQL WHERE 条件
        CheckService->>Redis: SET priv:filter:{userId}:{objectApiKey}
    end
    CheckService-->>PrivRPC: DataPermissionFilter
    PrivRPC-->>EntitySvc: Result<DataPermissionFilter>
```

**DataPermissionFilter 结构：**
```json
{
  "type": "RECORD_IDS | ALL | NONE",
  "recordIds": ["id1", "id2"],
  "sqlCondition": "owner_id = 'xxx' OR id IN (SELECT record_id FROM priv_share WHERE user_id = 'xxx')"
}
```

### 6.2 权限去规范化异步计算

```mermaid
sequenceDiagram
    participant Trigger
    participant MQ
    participant DenormConsumer
    participant DenormService
    participant DB
    participant Redis

    Trigger->>MQ: 发布权限变更事件
    MQ->>DenormConsumer: 消费事件
    DenormConsumer->>DenormService: triggerCalculation(event)
    DenormService->>DenormService: 计算影响范围
    DenormService->>DB: 分批查询受影响用户
    loop 每批用户
        DenormService->>DenormService: 计算数据权限
        DenormService->>DB: UPSERT priv_subject_share_data
    end
    DenormService->>Redis: 批量失效权限缓存
    DenormService->>MQ: 发布计算完成事件
```

---

## 7. 接口设计

### 7.1 REST 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/roles` | 创建角色 |
| POST | `/api/v1/roles/{id}/assign` | 分配角色给用户 |
| GET | `/api/v1/permissions/check` | 功能权限校验 |
| POST | `/api/v1/share-rules` | 创建共享规则 |
| GET | `/api/v1/position-tree` | 查询岗位树 |
| POST | `/api/v1/team-members` | 添加团队成员 |
| POST | `/api/v1/repair/recalculate` | 手动触发权限重算（运维） |

### 7.2 RPC 接口（core module）

```java
@FeignClient(name = "paas-privilege-service")
public interface PrivilegeApi {
    // 功能权限校验
    Result<Boolean> checkObjectPermission(String tenantId, String userId,
                                          String objectApiKey, String operation);

    // 获取数据权限过滤条件
    Result<DataPermissionFilter> getDataPermissionFilter(String tenantId,
                                                          String userId,
                                                          String objectApiKey);

    // 批量校验字段权限
    Result<Map<String, String>> getFieldPermissions(String tenantId,
                                                     String userId,
                                                     String objectApiKey);
}
```

---

## 8. 缓存策略

| 缓存 Key | 内容 | TTL | 失效时机 |
|---|---|---|---|
| `priv:func:{userId}:{objectApiKey}` | 功能权限 | 10min | 角色变更 |
| `priv:filter:{userId}:{objectApiKey}` | 数据权限过滤条件 | 5min | 权限重算完成 |
| `priv:field:{userId}:{objectApiKey}` | 字段权限 | 10min | 角色变更 |

---

## 9. 异常处理

| 异常场景 | 处理策略 |
|---|---|
| 权限计算超时 | 降级返回最保守权限（只读自己的记录） |
| MQ 消费失败 | 重试5次，失败写死信队列，告警人工处理 |
| 权限数据不一致 | 提供 `/repair/recalculate` 接口手动触发重算 |
| 循环岗位依赖 | 创建岗位时检测环路，拒绝操作 |


---

## 10. 数据存储说明

privilege-service **不直接维护独立的业务数据库表**。权限相关的元数据定义（角色、权限集、岗位、共享规则等）存储在 `paas-metarepo-service` 的 `xsy_metarepo` schema 中，通过 metarepo 的 RPC 接口读写。运行时权限计算结果（去规范化数据）存储在 Redis 缓存中，不持久化到独立 DB。

<!-- DDL 章节已移除，privilege-service 无独立数据库表 -->
