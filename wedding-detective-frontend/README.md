# 婚礼上的毒杀 - 前端

这是侦探游戏的前端页面，所有案件数据、搜证、审问、推理笔记和结案都通过后端接口完成。

## 运行顺序

先启动后端：

```text
http://localhost:8081/api
```

再启动前端：

```powershell
node server.js
```

打开：

```text
http://localhost:5180
```

## 后端接口

前端当前调用这些接口：

```text
POST /api/session/start
GET  /api/session/{id}
POST /api/evidence/inspect
POST /api/scene/{sceneId}/inspect
POST /api/interrogate/stream
POST /api/notes/analyze
POST /api/verdict
```

## 已接通流程

- 开场页：从后端创建会话并读取案件数据。
- 搜证页：场景切换、证物检查、场景搜查。
- 审问页：人物列表、自由提问、出示证物、DeepSeek 角色流式回答。
- 推理板：根据后端线索状态生成矛盾笔记。
- 结案页：提交指认对象、关键物品和推理理由。
- 结局页：展示后端判定的结局结果。

## 素材

图片资源位于：

```text
assets/characters/
assets/scenes/
```

后端返回的 `assetPath` 指向这些路径，因此替换图片时保持文件名不变即可。
