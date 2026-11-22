"""通用 ``.pez`` 谱包打包。

官方 PEZ 规范：PEZ 本质是 zip，内部文件（info.txt、info.yml、谱面 json、
音乐、曲绘）必须**平铺在压缩包根目录**。RPE/PhiEdit 桌面版读 ``info.txt``
（UTF-8 + CRLF，首行 ``#``），Phira 读 ``info.yml``。
"""

from __future__ import annotations

import json
import os
import struct
import tempfile
import time
import zipfile
import zlib

#: 允许的封面图片扩展名（同时作为 Phira/RPE 内部的格式标识）
COVER_EXT_MAP = {
    ".png": "png",
    ".jpg": "jpg",
    ".jpeg": "jpg",
    ".gif": "gif",
    ".webp": "webp",
    ".bmp": "bmp",
}


def is_image(data: bytes) -> bool:
    """按魔数粗略校验图片内容（避免把非图片塞进谱包）。"""
    if data[:8].startswith(b"\x89PNG\r\n\x1a\n"):
        return True
    if data[:2] == b"\xff\xd8":          # JPEG
        return True
    if data[:4] == b"GIF8":              # GIF
        return True
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return True
    if data[:2] == b"BM":                # BMP
        return True
    return False


def audio_length_sec(path: str) -> float | None:
    """尽力读取音频时长（秒）；读不出返回 None。wav 用标准库精确解析。"""
    try:
        if path.lower().endswith(".wav"):
            import wave

            with wave.open(path, "rb") as handle:
                return round(handle.getnframes() / float(handle.getframerate()), 3)
    except Exception:
        pass
    return None


def make_illustration(path: str, size: int = 512) -> None:
    """生成一张简洁的渐变占位封面 PNG（纯标准库实现）。"""
    width = height = size
    raw = bytearray()
    for y in range(height):
        raw.append(0)  # PNG 行过滤器：None
        for x in range(width):
            # 深蓝 -> 深紫 对角渐变
            r = int(30 + 60 * (x / width))
            g = int(30 + 20 * (y / height))
            b = int(70 + 80 * ((x + y) / (width + height)))
            raw += bytes((r, g, b))

    def _chunk(tag: bytes, data: bytes) -> bytes:
        body = struct.pack(">I", len(data)) + tag + data
        return body + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)  # 8bit RGB
    png = (b"\x89PNG\r\n\x1a\n" + _chunk(b"IHDR", ihdr)
           + _chunk(b"IDAT", zlib.compress(bytes(raw), 6)) + _chunk(b"IEND", b""))
    with open(path, "wb") as handle:
        handle.write(png)


def yaml_str(value: object) -> str:
    """把字符串安全地写成 YAML 标量（JSON 字符串是合法 YAML）。"""
    return json.dumps(str(value), ensure_ascii=False)


class PezPacker:
    """把 RPE 工程打包为通用 ``.pez``。

    ``owner`` 只需提供 ``cover_filename`` 与 ``audio_filename`` 两个属性，
    因此同一套打包逻辑既可服务录制导出，也可被自动制谱复用（显式传参）。
    """

    def __init__(self, upload_dir: str, export_dir: str, owner: object) -> None:
        self.upload_dir = upload_dir
        self.export_dir = export_dir
        self.owner = owner

    # -- 曲绘 ---------------------------------------------------------------
    def ensure_illustration(self) -> tuple[str, str]:
        """准备曲绘文件，返回 ``(包内文件名, 本机绝对路径)``。

        包内名固定为干净的 ASCII ``background.<ext>``：Phira 解压 .pez 后按文件名
        加载曲绘，中文/空格文件名在 zip 里极易被读成乱码 → 曲绘加载失败。
        """
        cover = getattr(self.owner, "cover_filename", None)
        if cover:
            src = os.path.join(self.upload_dir, cover)
            if os.path.isfile(src):
                ext = COVER_EXT_MAP.get(os.path.splitext(cover)[1].lower(), "png")
                return f"background.{ext}", src
        gen_path = os.path.join(tempfile.gettempdir(), "tb_illustration_cache.png")
        make_illustration(gen_path)
        return "background.png", gen_path

    # -- 打包 ---------------------------------------------------------------
    def build(
        self,
        rpe: dict,
        name: str,
        *,
        chart_id: str | None = None,
        output_dir: str | None = None,
        audio_source: str | None = None,
        cover_source: str | None = None,
        duration: float | None = None,
        export_meta: dict | None = None,
    ) -> tuple[str | None, str | None]:
        """返回 ``(pez 文件名, 提示语)``；失败时文件名为 None。"""
        meta = export_meta if export_meta is not None else self.owner.config["meta"]
        audio_filename = getattr(self.owner, "audio_filename", None)
        try:
            pid = chart_id or str(time.time_ns() // 1000)[-16:].zfill(16)
            if chart_id is None:
                in_name, illus_src = self.ensure_illustration()
                audio_source = os.path.join(self.upload_dir, audio_filename) if audio_filename else None
            elif cover_source is not None:
                illus_src = cover_source
                in_name = "background." + COVER_EXT_MAP.get(
                    os.path.splitext(cover_source)[1].lower(), "png")
            else:
                in_name = "background.png"
                illus_src = os.path.join(output_dir, in_name)
                make_illustration(illus_src)

            illus_ext = os.path.splitext(in_name)[1]
            a_ext = os.path.splitext(audio_source)[1].lower() if audio_source else ""
            proj = json.loads(json.dumps(rpe, ensure_ascii=False))
            proj["META"]["illustration"] = pid + illus_ext
            proj["META"]["background"] = pid + illus_ext
            if audio_source:
                proj["META"]["song"] = pid + a_ext
            music_src = audio_source if audio_source and os.path.isfile(audio_source) else None
            if chart_id is not None and music_src is None:
                raise FileNotFoundError("制谱音频已被移除")
            length = duration if duration is not None else (
                audio_length_sec(os.fspath(music_src)) if music_src else None)

            # --- info.txt（RPE/PhiEdit 桌面版专用，UTF-8 + CRLF，首行 "#"）---
            now = time.localtime()
            last_edit = "_".join(str(now[i]) for i in (0, 1, 2, 3, 4, 5)) + "_"
            try:
                level_val = int(str(meta.get("level") or "").strip())
            except ValueError:
                level_val = 0
            lines = [
                "#",
                f"Name: {meta.get('name') or name}",
                f"Path: {pid}",
                f"Song: {pid}{a_ext}",
                f"Picture: {pid}{illus_ext}",
                f"Chart: {pid}.json",
                f"Level: {level_val}",
                f"Composer: {meta.get('composer') or ''}",
                f"Charter: {meta.get('charter') or 'TouchBridge'}",
                f"LastEditTime: {last_edit}",
            ]
            if length is not None:
                lines.append(f"Length: {length}")
            lines.append("EditTime: 0.0")
            lines.append("Group: Default")

            # --- info.yml（Phira 专用，字段与 info.txt 指向同一套文件）---
            info_fields = {
                "name": meta.get("name") or name,
                "difficulty": 0,
                "level": str(meta.get("level") or "SP"),
                "charter": meta.get("charter") or "TouchBridge",
                "composer": meta.get("composer") or "",
                "illustrator": "",
                "chart": f"{pid}.json",
                "format": "rpe",
                "music": (pid + a_ext) if music_src else "",
                "illustration": pid + illus_ext,
            }
            yml = "\n".join(
                f"{k}: {yaml_str(v) if isinstance(v, str) else v}" for k, v in info_fields.items())

            pez_path = os.path.join(output_dir or self.export_dir, f"{name}.pez")
            os.makedirs(os.path.dirname(pez_path) or ".", exist_ok=True)
            with zipfile.ZipFile(pez_path, "w", zipfile.ZIP_DEFLATED) as archive:
                archive.writestr("info.txt", "\r\n".join(lines) + "\r\n")
                archive.writestr("info.yml", yml + "\n")
                archive.writestr(f"{pid}.json", json.dumps(proj, ensure_ascii=False, indent=2))
                archive.write(illus_src, pid + illus_ext)
                if music_src:
                    archive.write(music_src, pid + a_ext)

            note = None if music_src else "未导入音频，包内暂无音乐文件"
            return os.path.basename(pez_path), note
        except Exception as exc:  # 打包失败不阻塞主流程
            return None, f"pez 打包失败: {exc}"