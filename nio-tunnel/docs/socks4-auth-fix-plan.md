# SOCKS4/4a 本地认证绕过修复方案

本方案已实施：启用本地认证时拒绝 SOCKS4/4a，未启用时保留支持。
`Socks4AuthenticationIntegrationTest` 覆盖拒绝、零出站连接、兼容性及 HTTP/SOCKS5 认证回归。

## 策略与实现

在 `Socks5Handler.handleSocks4Command()` 入口、任何路由判断或出站连接之前，检查
`config.requireClientAuth()`。开启本地认证时，返回
`Socks4CommandStatus.REJECTED_OR_FAILED`（0x5B），写出响应后关闭客户端连接并立即返回。
不创建到目标或上游的连接，SOCKS4 与 SOCKS4a 统一处理。

未开启认证时，保留原有 CONNECT 行为。不将 USERID 当作密码认证依据，不新增允许绕过的配置。
记录客户端地址与“SOCKS4 不支持所要求的本地认证”的拒绝原因，不记录凭据。
README 已明确：启用本地认证后，需要使用支持用户名密码的 SOCKS5 客户端。

## 回归验收

- 开启认证时，直连、上游和 SOCKS4a 请求全部得到 0x5B，随后连接关闭。
- 目标与上游均未接收到出站连接。
- USERID 为空、任意值、等于配置用户名时结果一致。
- 未开启认证时，SOCKS4/4a CONNECT 和双向数据传输继续工作。
- HTTP 和 SOCKS5 现有认证行为保持兼容。
