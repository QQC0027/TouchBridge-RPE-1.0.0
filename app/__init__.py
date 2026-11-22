"""TouchBridge → RPE 一体化应用包。

模块划分（进程内，无任何端口与外部服务）：

- ``paths``     ：程序资源 / 用户数据目录解析与旧版数据迁移
- ``defaults``  ：默认配置
- ``eventbus``  ：进程内事件总线（替代 socket.io）
- ``engine``    ：节拍时钟、手势还原、RPE 生成（纯算法）
- ``pez``       ：通用 .pez 谱包打包
- ``core``      ：AppCore，状态 + 全部业务方法
- ``collector`` ：C# 采集子进程与 stdin/stdout 管道
- ``autochart_*``：自动制谱任务与算法
- ``ui_bridge`` ：pywebview 的 JS 桥（唯一界面入口）
"""

__all__ = ["paths", "defaults", "eventbus", "core", "collector", "ui_bridge"]