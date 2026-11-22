"""运行时路径解析。

一体化后只有两类目录：

* **资源目录**（``resource_dir``）：随程序分发的只读内容——``ui/index.html``、
  采集器 exe、``third_party``。打包成 exe 后位于 PyInstaller 的 ``_MEIPASS``。
* **数据目录**（``data_dir``）：可写的用户数据——``config.json``、``uploads``、
  ``exports``、``logs``。始终位于 exe 同级，整目录拷走即可备份，升级程序不丢谱面。
"""

from __future__ import annotations

import json
import os
import shutil
import sys

from . import defaults

COLLECTOR_EXE_NAME = "TouchBridge.exe"


def is_frozen() -> bool:
    return bool(getattr(sys, "frozen", False))


def resource_dir() -> str:
    """只读资源根目录。"""
    if is_frozen():
        return getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(sys.executable)))
    return os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def base_dir() -> str:
    """程序（exe）所在目录，同时也是便携数据目录的父目录。"""
    if is_frozen():
        return os.path.dirname(os.path.abspath(sys.executable))
    return resource_dir()


class Paths:
    def __init__(self, base: str | None = None) -> None:
        self.resource_dir = resource_dir()
        self.base_dir = os.path.abspath(base) if base else base_dir()
        self.data_dir = os.path.join(self.base_dir, "data")
        self.upload_dir = os.path.join(self.data_dir, "uploads")
        self.export_dir = os.path.join(self.data_dir, "exports")
        self.log_dir = os.path.join(self.data_dir, "logs")
        self.log_path = os.path.join(self.log_dir, "app.log")
        self.config_path = os.path.join(self.data_dir, "config.json")
        self.ui_html = os.path.join(self.resource_dir, "ui", "index.html")
        self.config_template = os.path.join(self.resource_dir, "data", "config.json")
        self.collector_exe = self._find_collector()

    # -- 采集器定位 ---------------------------------------------------------
    def _find_collector(self) -> str:
        candidates = [
            # 打包后：collector/TouchBridge.exe 被 --add-data 带进资源目录
            os.path.join(self.resource_dir, "collector", COLLECTOR_EXE_NAME),
            os.path.join(self.resource_dir, "collector", "dist", COLLECTOR_EXE_NAME),
            # 开发态：collector/dist/（build.bat 的输出）
            os.path.join(self.base_dir, "collector", "dist", COLLECTOR_EXE_NAME),
            # 开发态：dotnet publish 的默认落点
            os.path.join(self.base_dir, "collector", "bin", "Release", "net8.0-windows",
                         "win-x64", "publish", COLLECTOR_EXE_NAME),
            os.path.join(self.base_dir, "collector", "bin", "Debug", "net8.0-windows",
                         COLLECTOR_EXE_NAME),
        ]
        for candidate in candidates:
            if os.path.isfile(candidate):
                return candidate
        return candidates[0]

    # -- 初始化 -------------------------------------------------------------
    def ensure(self) -> list[str]:
        """建目录、铺默认配置、迁移旧版数据。返回给用户的提示信息列表。"""
        notes: list[str] = []
        for path in (self.data_dir, self.upload_dir, self.export_dir, self.log_dir):
            os.makedirs(path, exist_ok=True)

        notes.extend(self._migrate_legacy())
        notes.extend(self._seed_config())
        return notes

    def _seed_config(self) -> list[str]:
        if os.path.isfile(self.config_path):
            return []
        template = self.config_template
        if os.path.isfile(template) and os.path.abspath(template) != os.path.abspath(self.config_path):
            try:
                shutil.copy2(template, self.config_path)
                return []
            except OSError:
                pass
        try:
            with open(self.config_path, "w", encoding="utf-8") as handle:
                json.dump(defaults.new_config(), handle, ensure_ascii=False, indent=2)
        except OSError:
            return ["无法写入配置文件，设置将不会被保存"]
        return []

    def _migrate_legacy(self) -> list[str]:
        """旧版把数据放在 ``backend/``：首次启动时一次性搬到 ``data/``。"""
        legacy = os.path.join(self.base_dir, "backend")
        if not os.path.isdir(legacy):
            return []

        notes: list[str] = []
        moved_any = False

        legacy_config = os.path.join(legacy, "config.json")
        if os.path.isfile(legacy_config) and not os.path.isfile(self.config_path):
            try:
                shutil.move(legacy_config, self.config_path)
                moved_any = True
            except OSError as exc:
                notes.append(f"旧配置文件迁移失败：{exc}")

        for name, dest in (("uploads", self.upload_dir), ("exports", self.export_dir)):
            src = os.path.join(legacy, name)
            if not os.path.isdir(src):
                continue
            try:
                os.makedirs(dest, exist_ok=True)
                for entry in os.listdir(src):
                    source = os.path.join(src, entry)
                    target = os.path.join(dest, entry)
                    if os.path.exists(target):
                        continue
                    shutil.move(source, target)
                    moved_any = True
            except OSError as exc:
                notes.append(f"旧 {name} 目录迁移失败：{exc}")

        if moved_any:
            notes.append("已把旧版 backend/ 下的配置与谱面数据迁移到 data/")
        return notes

    # -- 便捷方法 -----------------------------------------------------------
    def upload_path(self, filename: str) -> str:
        return os.path.join(self.upload_dir, filename)

    def export_path(self, filename: str) -> str:
        return os.path.join(self.export_dir, filename)