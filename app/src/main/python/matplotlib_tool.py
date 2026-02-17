import os
import traceback
from typing import Dict, Any

os.environ.setdefault("MPLBACKEND", "Agg")


def run(code: str, output_path: str, sandbox_dir: str = None) -> Dict[str, Any]:
    """
    Execute matplotlib code with sandbox file access.
    
    Args:
        code: Python code using matplotlib
        output_path: Path to save the generated plot
        sandbox_dir: Optional sandbox directory for file access
    """
    try:
        import math
        import json
        import csv
        import sqlite3
        import numpy as np
        import pandas as pd
        import matplotlib
        import matplotlib.font_manager as fm
        from matplotlib.font_manager import FontProperties
        import matplotlib.pyplot as plt
        from PIL import Image

        def _setup_cjk_fonts() -> None:
            try:
                candidates = [
                    "/system/fonts/NotoSansCJK-Regular.ttc",
                    "/system/fonts/NotoSansCJKsc-Regular.otf",
                    "/system/fonts/NotoSansSC-Regular.otf",
                    "/system/fonts/NotoSansCJKsc-Regular.ttf",
                    "/system/fonts/NotoSansSC-Regular.ttf",
                    "/system/fonts/DroidSansFallback.ttf",
                    "/system/fonts/DroidSansFallbackFull.ttf",
                    "/system/fonts/SourceHanSansSC-Regular.otf",
                    "/system/fonts/SourceHanSansCN-Regular.otf",
                ]

                names = []
                for p in candidates:
                    if not os.path.exists(p):
                        continue
                    try:
                        fm.fontManager.addfont(p)
                        name = FontProperties(fname=p).get_name()
                        if name and name not in names:
                            names.append(name)
                    except Exception:
                        continue

                if names:
                    matplotlib.rcParams["font.family"] = "sans-serif"
                    matplotlib.rcParams["font.sans-serif"] = names + [
                        "DejaVu Sans",
                        "sans-serif",
                    ]
                    matplotlib.rcParams["axes.unicode_minus"] = False
            except Exception:
                pass

        _setup_cjk_fonts()
        
        # Setup sandbox helpers
        base_dir = sandbox_dir or os.getcwd()
        
        def read_file(path: str) -> str:
            """Read file from sandbox"""
            full_path = os.path.join(base_dir, path) if not os.path.isabs(path) else path
            with open(full_path, 'r', encoding='utf-8') as f:
                return f.read()
        
        def write_file(path: str, content: str) -> None:
            """Write file to sandbox"""
            full_path = os.path.join(base_dir, path) if not os.path.isabs(path) else path
            os.makedirs(os.path.dirname(full_path), exist_ok=True)
            with open(full_path, 'w', encoding='utf-8') as f:
                f.write(content)
        
        def list_files(path: str = ".") -> list:
            """List files in sandbox directory"""
            full_path = os.path.join(base_dir, path) if not os.path.isabs(path) else path
            return os.listdir(full_path)

        plt.close('all')

        g = {
            "__name__": "__main__",
            "plt": plt,
            "np": np,
            "pd": pd,
            "math": math,
            "json": json,
            "csv": csv,
            "sqlite3": sqlite3,
            "Image": Image,
            "matplotlib": matplotlib,
            "read_file": read_file,
            "write_file": write_file,
            "list_files": list_files,
        }
        l = {}

        exec(code, g, l)

        fig = plt.gcf()
        os.makedirs(os.path.dirname(output_path), exist_ok=True)
        fig.savefig(output_path, dpi=200, bbox_inches="tight")
        plt.close('all')

        return {"ok": True, "output_path": output_path}
    except Exception as e:
        return {
            "ok": False,
            "error": f"[{e.__class__.__name__}] {e}",
            "traceback": traceback.format_exc(),
        }
