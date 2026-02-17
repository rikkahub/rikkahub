"""
RikkaHub Sandbox Tool - 安全的文件系统操作和 Python 执行环境

【重要限制】
Chaquopy 不支持运行时安装 Python 包。使用 list_available_packages 查看所有预装包。

【可用工具分类】
文件操作: read, write, list, delete, mkdir, copy, move, stat, exists
压缩文件: unzip, zip_create
代码执行: python_exec, exec, exec_script
数据处理: process_image, convert_excel, extract_pdf_text, download_file
数据库: sqlite_query, sqlite_tables
版本控制: git_init, git_status, git_log, git_diff, git_add, git_commit, git_branch, git_checkout, git_rm, git_mv
多语言: exec_js, exec_lua
代码分析: analyze_code, compile_check
包管理: list_available_packages（查看预装包）
工具安装: install_tool

【示例】
{
    "operation": "python_exec",
    "code": "import numpy as np\nprint(np.sum([1,2,3]))"
}
"""

import os
import zipfile
import subprocess
import json
import shutil
import shlex
import base64
from typing import Dict, Any, List, Optional
from pathlib import Path

# Android 环境下的命令路径
ANDROID_PATHS = [
    '/system/bin',
    '/system/xbin',
    '/vendor/bin',
    '/data/data/com.termux/files/usr/bin',  # Termux (如果存在)
]

def _get_android_command(cmd: str) -> str:
    """在 Android 环境中查找命令完整路径"""
    # 如果已经是完整路径，直接返回
    if os.path.isabs(cmd) and os.path.exists(cmd):
        return cmd
    
    # 在常见路径中查找
    for path in ANDROID_PATHS:
        full_path = os.path.join(path, cmd)
        if os.path.exists(full_path):
            return full_path
    
    # 返回原命令，让系统尝试解析
    return cmd

def _to_json_serializable(obj: Any) -> Any:
    """将 Python 对象转为 JSON 可序列化格式"""
    if isinstance(obj, dict):
        return {k: _to_json_serializable(v) for k, v in obj.items()}
    elif isinstance(obj, list):
        return [_to_json_serializable(v) for v in obj]
    elif isinstance(obj, (int, float, str, bool)):
        return obj
    elif obj is None:
        return None
    else:
        return str(obj)

# 命令白名单 - 允许执行的基础命令
# 基于 Android Toybox 实际可用的命令
ALLOWED_COMMANDS = {
    # === 文件操作 (Toybox 可用) ===
    'ls', 'cat', 'grep', 'sed', 'awk', 'head', 'tail',
    'cp', 'mv', 'rm', 'mkdir', 'touch', 'chmod',
    'find', 'which', 'ln', 'readlink', 'realpath',
    'du', 'df', 'stat', 'file', 'basename', 'dirname',
    
    # === 压缩解压 (Toybox 可用) ===
    'tar', 'gzip', 'gunzip', 'bzip2', 'bunzip2', 'lzma',
    # 注意: unzip, zip, xz 可能不可用
    
    # === 文本处理 (Toybox 可用) ===
    'sort', 'uniq', 'wc', 'cut', 'tr', 'diff', 'patch',
    'tee', 'split', 'csplit', 'comm', 'nl', 'fmt', 'pr', 'fold',
    'rev', 'tac', 'hexdump', 'od', 'strings',
    # 注意: join 可能不可用
    
    # === 网络工具 (部分可用) ===
    'curl', 'ping', 'wget',  # curl 通常可用，wget 可能不可用
    
    # === 系统信息 (Toybox 可用) ===
    'echo', 'pwd', 'whoami', 'date', 'uname', 'env', 'printenv',
    'id', 'groups', 'uptime', 'hostname',
    
    # === 进程管理 (Toybox 可用) ===
    'ps', 'pgrep', 'pkill',
    # 注意: ps 使用 Toybox 语法 (ps -A 而非 ps aux)
    
    # === 编辑器/查看器 (部分可用) ===
    'vi', 'more',
    # 注意: vim, nano, less 不可用
}

# 用户通过 pip 安装的 Python 包命令白名单
# 这些是常用的数据处理/分析工具
PIP_COMMAND_ALLOWLIST = {
    # 数据处理
    'jq',  # JSON 处理
    'yq',  # YAML 处理  
    'csvkit',  # CSV 工具集
    'xmltodict',  # XML 转换
    
    # 文本处理
    'pygments',  # 语法高亮
    'markdown',  # Markdown 处理
    
    # 代码工具
    'black', 'yapf', 'autopep8',  # Python 格式化
    'pylint', 'flake8', 'mypy',  # Python 检查
    
    # 实用工具
    'httpie',  # HTTP 客户端
    'http-prompt',  # HTTP 交互
    'xh',  # HTTPie 替代
    
    # 文件处理
    'chardet',  # 编码检测
    'file-magic',  # 文件类型检测
}

# 已安装的 pip 命令缓存
_installed_pip_commands = set()

# 黑名单关键字 - 绝对禁止的操作
BLOCKED_KEYWORDS = [
    # 编译构建相关
    'javac', 'kotlin', 'gradle', 'ndk-build', 'make', 'cmake', 'gcc', 'g++', 'clang',
    # 包管理器
    'apt', 'apt-get', 'yum', 'dnf', 'pacman', 'brew', 'pkg',
    # 系统级危险命令
    'su', 'sudo', 'mount', 'umount', 'mkfs', 'fdisk', 'dd',
    'mkfs.ext', 'mkfs.ntfs', 'format',
    # 权限提升
    'chmod 777', 'chmod +s', 'chown root',
    # 敏感路径
    '/system', '/proc', '/sys', '/dev',
]

# 最大文件大小限制 (50MB)
MAX_FILE_SIZE = 50 * 1024 * 1024

# 命令执行超时 (30秒)
COMMAND_TIMEOUT = 30


def execute(operation: str, sandbox_path: str, params: Dict[str, Any]) -> str:
    """
    主入口函数 - 执行沙箱操作
    
    Args:
        operation: 操作类型
        sandbox_path: 沙箱根目录路径
        params: 操作参数
    
    Returns:
        JSON 字符串格式的操作结果
    """
    try:
        # 标准化路径
        sandbox_path = os.path.normpath(os.path.abspath(sandbox_path))
        
        # 验证沙箱目录存在
        if not os.path.exists(sandbox_path):
            os.makedirs(sandbox_path, exist_ok=True)
        
        # 根据操作类型分发
        handlers = {
            "unzip": _unzip_file,
            "zip_create": _create_zip,
            "exec": _exec_command,
            "list": _list_files,
            "read": _read_file,
            "write": _write_file,
            "delete": _delete_file,
            "mkdir": _make_directory,
            "copy": _copy_file,
            "move": _move_file,
            "stat": _file_stat,
            "exists": _file_exists,
            "python_exec": _python_exec,
            # 便利操作
            "process_image": _process_image,
            "convert_excel": _convert_excel,
            "extract_pdf_text": _extract_pdf_text,
            "download_file": _download_file,
            "sqlite_query": _sqlite_query,
            "sqlite_tables": _sqlite_tables,
            # Git 操作
            "git_init": _git_init,
            "git_status": _git_status,
            "git_log": _git_log,
            "git_diff": _git_diff,
            "git_add": _git_add,
            "git_commit": _git_commit,
            "git_branch": _git_branch,
            "git_checkout": _git_checkout,
            "git_rm": _git_rm,
            "git_mv": _git_mv,
            # Workflow checkpoint operations
            "git_checkpoint": _git_checkpoint,
            "git_restore": _git_restore,
            "git_list_checkpoints": _git_list_checkpoints,
            # Shell 脚本
            "exec_script": _exec_script,
            # 多语言支持
            "exec_js": _exec_javascript,
            "exec_lua": _exec_lua,
            "analyze_code": _analyze_code,
            # 编译验证
            "compile_check": _compile_check,
            # 工具安装
            "install_tool": _install_tool,
            # 包管理（基于 Chaquopy 预装环境）
            "list_available_packages": _list_available_packages,
        }
        
        handler = handlers.get(operation)
        if not handler:
            result = {
                "success": False,
                "error": f"Unknown operation: {operation}",
                "available_operations": list(handlers.keys())
            }
            return json.dumps(result)
        
        result = handler(sandbox_path, params)
        return json.dumps(_to_json_serializable(result))
        
    except Exception as e:
        import traceback
        result = {
            "success": False,
            "error": str(e),
            "traceback": traceback.format_exc()
        }
        return json.dumps(result)


def _validate_path(sandbox_path: str, user_path: str) -> str:
    """
    验证并规范化用户提供的文件路径
    防止目录遍历攻击
    """
    if user_path is None:
        raise ValueError("Path cannot be None")
    
    # 规范化路径
    full_path = os.path.normpath(os.path.join(sandbox_path, user_path))
    
    # 安全检查：确保路径在沙箱内
    if not full_path.startswith(os.path.normpath(sandbox_path)):
        raise ValueError(f"Path traversal detected: {user_path}")
    
    return full_path


def _is_safe_command(command: str) -> tuple[bool, str]:
    """
    检查命令是否安全
    返回: (是否安全, 错误信息)
    """
    if not command or not isinstance(command, str):
        return False, "Command must be a non-empty string"
    
    command_lower = command.lower()
    
    # 检查黑名单关键字
    for keyword in BLOCKED_KEYWORDS:
        if keyword.lower() in command_lower:
            return False, f"Command contains blocked keyword: {keyword}"
    
    # 解析命令
    try:
        args = shlex.split(command)
    except ValueError as e:
        return False, f"Invalid command syntax: {e}"
    
    if not args:
        return False, "Empty command"
    
    cmd = args[0]
    
    # 检查是否在白名单
    if cmd in ALLOWED_COMMANDS:
        return True, ""
    
    # 检查是否是已安装的 pip 命令
    if cmd in _installed_pip_commands:
        return True, ""
    
    # 检查是否是预授权的 pip 包命令
    if cmd in PIP_COMMAND_ALLOWLIST:
        # 自动添加到已安装缓存（即使还没安装，允许尝试执行）
        _installed_pip_commands.add(cmd)
        return True, ""
    
    return False, f"Command '{cmd}' is not in whitelist. Allowed system commands: {', '.join(sorted(ALLOWED_COMMANDS))}. You can install additional tools using install_tool operation."


def _unzip_file(sandbox_path: str, params: Dict[str, Any]) -> Dict[str, Any]:
    """解压 ZIP 文件"""
    zip_path = params.get('zip_path')
    target_dir = params.get('target_dir', '.')
    
    if not zip_path:
        return {"success": False, "error": "Missing required parameter: zip_path"}
    
    full_zip_path = _validate_path(sandbox_path, zip_path)
    full_target_dir = _validate_path(sandbox_path, target_dir)
    
    if not os.path.exists(full_zip_path):
        return {"success": False, "error": f"ZIP file not found: {zip_path}"}
    
    if not zipfile.is_zipfile(full_zip_path):
        return {"success": False, "error": f"Not a valid ZIP file: {zip_path}"}
    
    # 确保目标目录存在
    os.makedirs(full_target_dir, exist_ok=True)
    
    extracted_files = []
    with zipfile.ZipFile(full_zip_path, 'r') as zf:
        # 检查 Zip Slip
        for member in zf.namelist():
            member_path = os.path.normpath(os.path.join(full_target_dir, member))
            if not member_path.startswith(os.path.normpath(full_target_dir)):
                return {
                    "success": False, 
                    "error": f"Zip Slip attack detected in file: {member}"
                }
        
        # 解压
        zf.extractall(full_target_dir)
        extracted_files = zf.namelist()
    
    # Generate human-readable stdout
    file_list = []
    for f in extracted_files[:20]:
        icon = "📁" if f.endswith("/") else "📄"
        file_list.append(f"  {icon} {f}")
    if len(extracted_files) > 20:
        file_list.append(f"  ... and {len(extracted_files) - 20} more files")
    stdout_lines = [f"📦 解压文件: {zip_path}", f"📂 目标目录: {target_dir}", f"📊 共解压 {len(extracted_files)} 个文件", ""]
    if file_list:
        stdout_lines.extend(file_list)
    else:
        stdout_lines.append("  (空压缩包)")
    stdout = "\n".join(stdout_lines)
    return {
        "success": True,
        "data": f"Extracted {len(extracted_files)} files to {target_dir}",
        "stdout": stdout,
        "files": extracted_files,
        "target_dir": target_dir
    }


def _create_zip(sandbox_path: str, params: Dict[str, Any]) -> Dict[str, Any]:
    """创建 ZIP 文件"""
    source_paths = params.get('source_paths', [])
    zip_name = params.get('zip_name')
    
    if not zip_name:
        return {"success": False, "error": "Missing required parameter: zip_name"}
    
    if not source_paths:
        return {"success": False, "error": "Missing required parameter: source_paths"}
    
    # 确保 zip 名以 .zip 结尾
    if not zip_name.endswith('.zip'):
        zip_name += '.zip'
    
    zip_path = _validate_path(sandbox_path, zip_name)
    
    created_files = []
    with zipfile.ZipFile(zip_path, 'w', zipfile.ZIP_DEFLATED) as zf:
        for source in source_paths:
            full_source = _validate_path(sandbox_path, source)
            
            if os.path.isfile(full_source):
                arcname = os.path.basename(source)
                zf.write(full_source, arcname)
                created_files.append(arcname)
            elif os.path.isdir(full_source):
                for root, dirs, files in os.walk(full_source):
                    for file in files:
                        file_path = os.path.join(root, file)
                        arcname = os.path.relpath(file_path, sandbox_path)
                        zf.write(file_path, arcname)
                        created_files.append(arcname)
    
    file_size = os.path.getsize(zip_path)
    
    return {
        "success": True,
        "data": f"Created {zip_name} ({file_size} bytes)",
        "file_name": zip_name,
        "file_size": file_size,
        "files_count": len(created_files),
        "file_path": zip_name  # 使用相对路径，让 App 通过 FileProvider 生成 URI
    }


def _exec_command(sandbox_path: str, params: Dict[str, Any]) -> Dict[str, Any]:
    """执行命令 (适配 Android 环境)"""
    command = params.get('command')
    
    if not command:
        return {"success": False, "error": "Missing required parameter: command"}
    
    # 安全检查
    is_safe, error_msg = _is_safe_command(command)
    if not is_safe:
        return {"success": False, "error": error_msg}
    
    try:
        args = shlex.split(command)
        
        # 在 Android 上查找命令完整路径
        if args:
            cmd = _get_android_command(args[0])
            args[0] = cmd
        
        # 设置受限环境 (Android 兼容)
        env = os.environ.copy() if 'os' in dir() else {}
        env['HOME'] = sandbox_path
        env['PWD'] = sandbox_path
        env['TMPDIR'] = os.path.join(sandbox_path, '.tmp')
        
        # 确保临时目录存在
        os.makedirs(env['TMPDIR'], exist_ok=True)
        
        # Android 上通常使用 /system/bin/sh
        shell_path = '/system/bin/sh'
        if os.path.exists(shell_path):
            # 通过 sh -c 执行，更兼容 Android
            full_cmd = ' '.join(shlex.quote(arg) for arg in args)
            result = subprocess.run(
                [shell_path, '-c', full_cmd],
                cwd=sandbox_path,
                capture_output=True,
                text=True,
                timeout=COMMAND_TIMEOUT,
                env=env
            )
        else:
            # 直接执行
            result = subprocess.run(
                args,
                cwd=sandbox_path,
                capture_output=True,
                text=True,
                timeout=COMMAND_TIMEOUT,
                env=env
            )
        
        return {
            "success": result.returncode == 0,
            "stdout": result.stdout[:10000] if result.stdout else "",  # 限制输出大小
            "stderr": result.stderr[:5000] if result.stderr else "",
            "returncode": result.returncode
        }
        
    except subprocess.TimeoutExpired:
        return {
            "success": False,
            "error": f"Command timeout (max {COMMAND_TIMEOUT}s)",
            "stdout": "",
            "stderr": "",
            "returncode": -1
        }
    except FileNotFoundError as e:
        return {
            "success": False,
            "error": f"Command not found: {str(e)}. Note: Many Linux commands are not available on Android.",
            "stdout": "",
            "stderr": str(e),
            "returncode": -1
        }
    except Exception as e:
        return {
            "success": False,
            "error": f"Execution failed: {str(e)}",
            "stdout": "",
            "stderr": str(e),
            "returncode": -1
        }


def _list_files(sandbox_path: str, params: Dict[str, Any]) -> Dict[str, Any]:
    """列出目录内容"""
    path = params.get('path', '.')
    show_hidden = params.get('show_hidden', False)
    
    full_path = _validate_path(sandbox_path, path)
    
    if not os.path.exists(full_path):
        return {"success": False, "error": f"Path not found: {path}"}
    
    if not os.path.isdir(full_path):
        return {"success": False, "error": f"Not a directory: {path}"}
    
    files = []
    try:
        items = os.listdir(full_path)
        for item in items:
            # 跳过隐藏文件
            if not show_hidden and item.startswith('.'):
                continue
            
            item_path = os.path.join(full_path, item)
            stat = os.stat(item_path)
            
            files.append({
                "name": item,
                "type": "dir" if os.path.isdir(item_path) else "file",
                "size": stat.st_size if os.path.isfile(item_path) else None,
                "modified": stat.st_mtime,
                "path": os.path.relpath(item_path, sandbox_path)
            })
        
        # 排序：目录在前，文件在后，按名称排序
        files.sort(key=lambda x: (0 if x["type"] == "dir" else 1, x["name"].lower()))
        
    except PermissionError:
        return {"success": False, "error": f"Permission denied: {path}"}
    
    return {
        "success": True,
        "data": files,
        "path": path,
        "total": len(files)
    }


def _read_file(sandbox_path: str, params: Dict[str, Any]) -> Dict[str, Any]:
    """读取文件内容"""
    # 支持多种参数名：file_path 或 path
    file_path = params.get('file_path') or params.get('path')
    encoding = params.get('encoding', 'utf-8')
    limit = params.get('limit', 1000)  # 默认最多读取1000行
    
    if not file_path:
        return {"success": False, "error": "Missing required parameter: file_path (or path)"}
    
    full_path = _validate_path(sandbox_path, file_path)
    
    if not os.path.exists(full_path):
        return {"success": False, "error": f"File not found: {file_path}"}
    
    if os.path.isdir(full_path):
        return {"success": False, "error": f"Is a directory: {file_path}"}
    
    # 检查文件大小
    file_size = os.path.getsize(full_path)
    if file_size > MAX_FILE_SIZE:
        return {
            "success": False, 
            "error": f"File too large ({file_size} bytes), max: {MAX_FILE_SIZE} bytes"
        }
    
    try:
        with open(full_path, 'r', encoding=encoding, errors='replace') as f:
            if limit:
                lines = []
                for i, line in enumerate(f):
                    if i >= limit:
                        lines.append(f"\n... ({limit} lines shown, file truncated)")
                        break
                    lines.append(line)
                content = ''.join(lines)
            else:
                content = f.read()
        
        return {
            "success": True,
            "data": content,
            "file_path": file_path,
            "size": file_size,
            "lines": content.count('\n') + 1
        }
        
    except UnicodeDecodeError:
        # 二进制文件尝试以 base64 返回
        import base64
        with open(full_path, 'rb') as f:
            data = f.read()
        return {
            "success": True,
            "data": base64.b64encode(data).decode('utf-8'),
            "encoding": "base64",
            "file_path": file_path,
            "size": file_size
        }
    except Exception as e:
        return {"success": False, "error": f"Read failed: {str(e)}"}


def _write_file(sandbox_path: str, params: Dict[str, Any]) -> Dict[str, Any]:
    """写入文件"""
    # 支持多种参数名：file_path 或 path
    file_path = params.get('file_path') or params.get('path')
    content = params.get('content')
    encoding = params.get('encoding', 'utf-8')
    append = params.get('append', False)
    
    if not file_path:
        return {"success": False, "error": "Missing required parameter: file_path (or path)"}
    
    if content is None:
        return {"success": False, "error": "Missing required parameter: content"}
    
    full_path = _validate_path(sandbox_path, file_path)
    
    # 确保目录存在
    dir_path = os.path.dirname(full_path)
    if dir_path:
        os.makedirs(dir_path, exist_ok=True)
    
    try:
        mode = 'a' if append else 'w'
        with open(full_path, mode, encoding=encoding) as f:
            f.write(content)
        
        file_size = os.path.getsize(full_path)
        
        return {
            "success": True,
            "data": f"{'Appended to' if append else 'Written to'} {file_path}",
            "file_path": file_path,
            "size": file_size,
            "bytes_written": len(content.encode(encoding))
        }
        
    except Exception as e:
        return {"success": False, "error": f"Write failed: {str(e)}"}


def _delete_file(sandbox_path: str, params: Dict[str, Any]) -> Dict[str, Any]:
    """删除文件或目录"""
    # 支持多种参数名：file_path 或 path
    file_path = params.get('file_path') or params.get('path')
    recursive = params.get('recursive', False)
    
    if not file_path:
        return {"success": False, "error": "Missing required parameter: file_path (or path)"}
    
    full_path = _validate_path(sandbox_path, file_path)
    
    if not os.path.exists(full_path):
        return {"success": False, "error": f"Path not found: {file_path}"}
    
    try:
        if os.path.isdir(full_path):
            if recursive:
                shutil.rmtree(full_path)
                return {"success": True, "data": f"Directory deleted recursively: {file_path}"}
            else:
                os.rmdir(full_path)
                return {"success": True, "data": f"Directory deleted: {file_path}"}
        else:
            os.remove(full_path)
            return {"success": True, "data": f"File deleted: {file_path}"}
            
    except OSError as e:
        return {"success": False, "error": f"Delete failed: {str(e)}"}


def _make_directory(sandbox_path: str, params: Dict[str, Any]) -> Dict[str, Any]:
    """创建目录"""
    dir_path = params.get('dir_path')
    parents = params.get('parents', True)
    
    if not dir_path:
        return {"success": False, "error": "Missing required parameter: dir_path"}
    
    full_path = _validate_path(sandbox_path, dir_path)
    
    try:
        if parents:
            os.makedirs(full_path, exist_ok=True)
        else:
            os.mkdir(full_path)
        
        return {"success": True, "data": f"Directory created: {dir_path}"}
        
    except FileExistsError:
        return {"success": False, "error": f"Directory already exists: {dir_path}"}
    except Exception as e:
        return {"success": False, "error": f"Create directory failed: {str(e)}"}


def _copy_file(sandbox_path: str, params: Dict[str, Any]) -> Dict[str, Any]:
    """复制文件或目录"""
    src = params.get('src')
    dst = params.get('dst')
    
    if not src or not dst:
        return {"success": False, "error": "Missing required parameters: src and dst"}
    
    full_src = _validate_path(sandbox_path, src)
    full_dst = _validate_path(sandbox_path, dst)
    
    if not os.path.exists(full_src):
        return {"success": False, "error": f"Source not found: {src}"}
    
    try:
        if os.path.isdir(full_src):
            shutil.copytree(full_src, full_dst)
        else:
            # 确保目标目录存在
            dst_dir = os.path.dirname(full_dst)
            if dst_dir:
                os.makedirs(dst_dir, exist_ok=True)
            
            # 先尝试使用 copy2（复制内容和元数据）
            try:
                shutil.copy2(full_src, full_dst)
            except (PermissionError, OSError) as e:
                # 如果 copy2 失败（通常是元数据问题），尝试使用 copy（仅复制内容）
                # 并检查文件是否已经成功复制
                if os.path.exists(full_dst) and os.path.getsize(full_dst) == os.path.getsize(full_src):
                    # 文件已成功复制，只是元数据（权限/时间戳）设置失败
                    return {
                        "success": True, 
                        "data": f"Copied {src} to {dst} (content copied, metadata warning: {str(e)})"
                    }
                else:
                    # 文件未成功复制，尝试基本的 copy
                    shutil.copy(full_src, full_dst)
        
        # 验证复制是否成功
        if os.path.exists(full_dst):
            src_size = os.path.getsize(full_src)
            dst_size = os.path.getsize(full_dst)
            if src_size == dst_size:
                return {"success": True, "data": f"Copied {src} to {dst} ({dst_size} bytes)"}
            else:
                return {"success": False, "error": f"Copy incomplete: source {src_size} bytes, destination {dst_size} bytes"}
        else:
            return {"success": False, "error": f"Copy failed: destination file not created"}
        
    except PermissionError as e:
        # 检查文件是否实际上已被复制（某些情况下权限错误发生在元数据修改阶段）
        if os.path.exists(full_dst):
            return {
                "success": True, 
                "data": f"Copied {src} to {dst} (with permission warning: {str(e)})"
            }
        return {"success": False, "error": f"Permission denied: {str(e)}"}
    except Exception as e:
        # 最后的检查：如果文件存在且大小相同，认为复制成功
        if os.path.exists(full_dst) and os.path.exists(full_src):
            try:
                if os.path.getsize(full_dst) == os.path.getsize(full_src):
                    return {
                        "success": True, 
                        "data": f"Copied {src} to {dst} (with warning: {str(e)})"
                    }
            except:
                pass
        return {"success": False, "error": f"Copy failed: {str(e)}"}


def _move_file(sandbox_path: str, params: Dict[str, Any]) -> Dict[str, Any]:
    """移动/重命名文件或目录

    支持两种参数名：
    - src/dst (推荐)
    - source/destination (兼容 Kotlin 代码)
    """
    # 支持两种参数名
    src = params.get('src') or params.get('source')
    dst = params.get('dst') or params.get('destination')

    if not src or not dst:
        return {"success": False, "error": "Missing required parameters: src/source and dst/destination"}
    
    full_src = _validate_path(sandbox_path, src)
    full_dst = _validate_path(sandbox_path, dst)
    
    if not os.path.exists(full_src):
        return {"success": False, "error": f"Source not found: {src}"}
    
    try:
        # 确保目标目录存在
        dst_dir = os.path.dirname(full_dst)
        if dst_dir:
            os.makedirs(dst_dir, exist_ok=True)
        
        shutil.move(full_src, full_dst)
        
        return {"success": True, "data": f"Moved {src} to {dst}"}
        
    except Exception as e:
        return {"success": False, "error": f"Move failed: {str(e)}"}


def _file_stat(sandbox_path: str, params: Dict[str, Any]) -> Dict[str, Any]:
    """获取文件/目录信息"""
    # 支持多种参数名：file_path 或 path
    file_path = params.get('file_path') or params.get('path')
    
    if not file_path:
        return {"success": False, "error": "Missing required parameter: file_path (or path)"}
    
    full_path = _validate_path(sandbox_path, file_path)
    
    if not os.path.exists(full_path):
        return {"success": False, "error": f"Path not found: {file_path}"}
    
    try:
        stat = os.stat(full_path)
        
        return {
            "success": True,
            "data": {
                "name": os.path.basename(file_path),
                "path": file_path,
                "type": "dir" if os.path.isdir(full_path) else "file",
                "size": stat.st_size,
                "created": stat.st_ctime,
                "modified": stat.st_mtime,
                "accessed": stat.st_atime,
                "permissions": oct(stat.st_mode)[-3:],
            }
        }
        
    except Exception as e:
        return {"success": False, "error": f"Stat failed: {str(e)}"}


def _file_exists(sandbox_path: str, params: Dict[str, Any]) -> Dict[str, Any]:
    """检查文件/目录是否存在"""
    # 支持多种参数名：file_path 或 path
    file_path = params.get('file_path') or params.get('path')
    
    if not file_path:
        return {"success": False, "error": "Missing required parameter: file_path (or path)"}
    
    full_path = _validate_path(sandbox_path, file_path)
    
    exists = os.path.exists(full_path)
    is_file = os.path.isfile(full_path) if exists else False
    is_dir = os.path.isdir(full_path) if exists else False
    
    return {
        "success": True,
        "data": {
            "exists": exists,
            "is_file": is_file,
            "is_directory": is_dir,
            "path": file_path
        }
    }


def _list_available_packages(sandbox_path: str, params: Dict[str, Any]) -> Dict[str, Any]:
    """列出沙箱环境中可用的 Python 包（基于已成功导入的模块）"""
    global _PREIMPORTED_MODULES
    
    # 初始化预导入模块
    if not _PREIMPORTED_MODULES:
        _PREIMPORTED_MODULES = _init_preimported_modules()
    
    # 提取包名（移除别名，只保留主模块名）
    packages = []
    for name, module in _PREIMPORTED_MODULES.items():
        # 过滤掉别名（如 'np' 是 numpy 的别名）
        if name not in ['np', 'pd']:
            packages.append(name)
    
    # 格式化输出
    package_list = '\n'.join(sorted(packages))
    
    return {
        "success": True,
        "packages": package_list,
        "package_count": len(packages),
        "message": f"Found {len(packages)} available packages in Chaquopy sandbox environment",
        "source": "chaquopy_pre_imported"
    }


# 兼容旧接口
run = execute


# 预导入的常用模块，AI 执行代码时可直接使用
_PREIMPORTED_MODULES = {}

def _init_preimported_modules():
    """初始化预导入的模块字典"""
    modules = {}
    
    # 标准库
    try:
        import json as _json
        modules['json'] = _json
    except: pass
    
    try:
        import csv as _csv
        modules['csv'] = _csv
    except: pass
    
    try:
        import re as _re
        modules['re'] = _re
    except: pass
    
    try:
        import math as _math
        modules['math'] = _math
    except: pass
    
    try:
        import random as _random
        modules['random'] = _random
    except: pass
    
    try:
        import datetime as _datetime
        modules['datetime'] = _datetime
    except: pass
    
    try:
        from dateutil import parser as _dateutil_parser
        modules['dateutil_parser'] = _dateutil_parser
    except: pass
    
    try:
        import itertools as _itertools
        modules['itertools'] = _itertools
    except: pass
    
    try:
        import collections as _collections
        modules['collections'] = _collections
    except: pass
    
    try:
        import hashlib as _hashlib
        modules['hashlib'] = _hashlib
    except: pass
    
    try:
        import base64 as _base64
        modules['base64'] = _base64
    except: pass
    
    try:
        import io as _io
        modules['io'] = _io
    except: pass
    
    try:
        import pathlib as _pathlib
        modules['pathlib'] = _pathlib
    except: pass
    
    try:
        import sqlite3 as _sqlite3
        modules['sqlite3'] = _sqlite3
    except: pass
    
    # 数据处理
    try:
        import numpy as _np
        modules['np'] = modules['numpy'] = _np
    except: pass
    
    try:
        import pandas as _pd
        modules['pd'] = modules['pandas'] = _pd
    except: pass
    
    # 图像处理
    try:
        from PIL import Image as _Image, ImageOps as _ImageOps, ImageFilter as _ImageFilter
        modules['Image'] = _Image
        modules['ImageOps'] = _ImageOps
        modules['ImageFilter'] = _ImageFilter
    except: pass
    
    # 网络请求
    try:
        import requests as _requests
        modules['requests'] = _requests
    except: pass
    
    try:
        from bs4 import BeautifulSoup as _BeautifulSoup
        modules['BeautifulSoup'] = _BeautifulSoup
    except: pass
    
    # PDF处理
    try:
        from PyPDF2 import PdfReader as _PdfReader, PdfWriter as _PdfWriter
        modules['PdfReader'] = _PdfReader
        modules['PdfWriter'] = _PdfWriter
    except: pass
    
    # YAML/TOML
    try:
        import yaml as _yaml
        modules['yaml'] = _yaml
    except: pass
    
    try:
        import toml as _toml
        modules['toml'] = _toml
    except: pass
    
    return modules


def _python_exec(sandbox_path: str, params: Dict[str, Any]) -> Dict[str, Any]:
    """
    执行 Python 代码（使用 Chaquopy 环境）
    这是绕过 Android shell 限制的主要方式
    
    预导入的模块可直接使用:
    - json, csv, re, math, random, datetime, hashlib, base64
    - np (numpy), pd (pandas), Image (PIL)
    - requests, BeautifulSoup (bs4)
    - PdfReader, PdfWriter (PyPDF2)
    - yaml, toml
    
    辅助函数:
    - read_file(path): 读取文本文件
    - write_file(path, content): 写入文本文件
    - list_files(path='.'): 列出目录文件
    - download(url, save_path=None): 下载文件
    """
    global _PREIMPORTED_MODULES
    
    code = params.get('code')
    script_path = params.get('script_path')  # 可选：从文件读取代码
    
    if not code and not script_path:
        return {"success": False, "error": "Missing required parameter: code or script_path"}
    
    if script_path:
        full_script_path = _validate_path(sandbox_path, script_path)
        if not os.path.exists(full_script_path):
            return {"success": False, "error": f"Script file not found: {script_path}"}
        try:
            with open(full_script_path, 'r', encoding='utf-8') as f:
                code = f.read()
        except Exception as e:
            return {"success": False, "error": f"Failed to read script: {str(e)}"}
    
    # 安全检查：禁止的危险操作
    dangerous_patterns = ['os.system', 'subprocess', 'eval(', 'exec(', '__import__']
    for dangerous in dangerous_patterns:
        if dangerous in code:
            return {"success": False, "error": f"Security check failed: forbidden pattern '{dangerous}'"}
    
    # 初始化预导入模块
    if not _PREIMPORTED_MODULES:
        _PREIMPORTED_MODULES = _init_preimported_modules()
    
    # 创建执行环境
    import io
    import sys
    import traceback
    
    # 保存原始 stdout/stderr
    old_stdout = sys.stdout
    old_stderr = sys.stderr
    
    # 创建新的输出捕获
    stdout_capture = io.StringIO()
    stderr_capture = io.StringIO()
    
    # 辅助函数：安全读取文件
    def _safe_read_file(path: str, encoding: str = 'utf-8') -> str:
        full_path = _validate_path(sandbox_path, path)
        with open(full_path, 'r', encoding=encoding, errors='replace') as f:
            return f.read()
    
    # 辅助函数：安全写入文件
    def _safe_write_file(path: str, content: str, encoding: str = 'utf-8'):
        full_path = _validate_path(sandbox_path, path)
        os.makedirs(os.path.dirname(full_path), exist_ok=True)
        with open(full_path, 'w', encoding=encoding) as f:
            f.write(content)
    
    # 辅助函数：列出文件
    def _safe_list_files(path: str = '.') -> List[str]:
        full_path = _validate_path(sandbox_path, path)
        return os.listdir(full_path)
    
    # 辅助函数：下载文件
    def _safe_download(url: str, save_path: str = None) -> str:
        if 'requests' not in _PREIMPORTED_MODULES:
            raise ImportError("requests module not available")
        resp = _PREIMPORTED_MODULES['requests'].get(url, timeout=30)
        resp.raise_for_status()
        if save_path:
            full_path = _validate_path(sandbox_path, save_path)
            with open(full_path, 'wb') as f:
                f.write(resp.content)
            return save_path
        return resp.text
    
    # 准备执行环境 - 包含预导入模块
    exec_globals = {
        '__builtins__': __builtins__,
        '__name__': '__main__',
        '__file__': os.path.join(sandbox_path, 'script.py'),
        # 预导入模块
        **_PREIMPORTED_MODULES,
        # 辅助函数
        'read_file': _safe_read_file,
        'write_file': _safe_write_file,
        'list_files': _safe_list_files,
        'download': _safe_download,
    }
    exec_locals = {}
    
    # 修改工作目录
    original_cwd = os.getcwd()
    os.chdir(sandbox_path)
    
    # 添加沙箱的 site-packages 到 Python 路径，以便 import 用户安装的包
    site_packages = os.path.join(sandbox_path, 'lib', 'python3.11', 'site-packages')
    if os.path.exists(site_packages) and site_packages not in sys.path:
        sys.path.insert(0, site_packages)
    
    try:
        # 重定向输出
        sys.stdout = stdout_capture
        sys.stderr = stderr_capture
        
        # 执行代码
        exec(code, exec_globals, exec_locals)
        
        # 恢复输出
        sys.stdout = old_stdout
        sys.stderr = old_stderr
        
        # 获取输出
        stdout_output = stdout_capture.getvalue()
        stderr_output = stderr_capture.getvalue()
        
        # 恢复工作目录
        os.chdir(original_cwd)
        
        # 修复：确保stdout不为None，且处理空输出情况
        result = {
            "success": True,
            "stdout": stdout_output if stdout_output else "",
            "stderr": stderr_output if stderr_output else None,
        }
        
        # 如果代码中定义了 result 变量，包含它
        if 'result' in exec_locals:
            try:
                result['result'] = _to_json_serializable(exec_locals['result'])
            except:
                result['result'] = str(exec_locals['result'])
        
        return result
        
    except Exception as e:
        # 恢复输出（确保在任何情况下都恢复）
        sys.stdout = old_stdout
        sys.stderr = old_stderr
        os.chdir(original_cwd)
        
        # 获取错误信息
        exc_type, exc_value, exc_traceback = sys.exc_info()
        error_msg = ''.join(traceback.format_exception(exc_type, exc_value, exc_traceback))
        
        # 修复：确保返回完整的错误信息
        return {
            "success": False,
            "error": f"Python execution failed: {str(e)}",
            "stdout": stdout_capture.getvalue(),
            "stderr": stderr_capture.getvalue(),
            "traceback": error_msg
        }


# =============================================================================
# 便利操作 - 常用功能的封装
# =============================================================================

def _process_image(sandbox_path: str, params: Dict[str, Any]) -> Dict[str, Any]:
    """
    图片处理便利操作
    
    Params:
        - input_path: 输入图片路径
        - output_path: 输出图片路径（可选，默认覆盖原文件或加后缀）
        - operation: 操作类型 - resize, convert, compress, thumbnail, grayscale
        - width: 目标宽度（resize/thumbnail）
        - height: 目标高度（resize/thumbnail，可选）
        - format: 目标格式（convert）- JPEG, PNG, WEBP
        - quality: 压缩质量 1-95（compress，默认 85）
    """
    try:
        from PIL import Image, ImageOps
    except ImportError:
        return {"success": False, "error": "PIL (Pillow) not available"}
    
    input_path = params.get('input_path')
    output_path = params.get('output_path')
    operation = params.get('operation', 'resize')
    
    if not input_path:
        return {"success": False, "error": "Missing input_path parameter"}
    
    try:
        full_input = _validate_path(sandbox_path, input_path)
        if not os.path.exists(full_input):
            return {"success": False, "error": f"Input file not found: {input_path}"}
        
        # 打开图片
        img = Image.open(full_input)
        original_size = img.size
        original_format = img.format
        
        # 执行操作
        if operation == 'resize':
            width = params.get('width')
            height = params.get('height')
            if not width:
                return {"success": False, "error": "Resize requires width parameter"}
            if height:
                img = img.resize((width, height), Image.Resampling.LANCZOS)
            else:
                # 保持宽高比
                ratio = width / original_size[0]
                height = int(original_size[1] * ratio)
                img = img.resize((width, height), Image.Resampling.LANCZOS)
        
        elif operation == 'thumbnail':
            max_size = params.get('width', 800)
            img.thumbnail((max_size, max_size), Image.Resampling.LANCZOS)
        
        elif operation == 'grayscale':
            img = ImageOps.grayscale(img)
        
        elif operation == 'convert':
            fmt = params.get('format', 'JPEG').upper()
            if img.mode in ('RGBA', 'P') and fmt == 'JPEG':
                img = img.convert('RGB')
        
        elif operation == 'compress':
            quality = params.get('quality', 85)
            # 压缩通过保存时设置质量实现
            pass
        
        else:
            return {"success": False, "error": f"Unknown operation: {operation}"}
        
        # 确定输出路径
        if not output_path:
            name, ext = os.path.splitext(input_path)
            if operation == 'convert':
                fmt = params.get('format', 'JPEG').lower()
                output_path = f"{name}_converted.{fmt}"
            else:
                output_path = f"{name}_{operation}{ext}"
        
        full_output = _validate_path(sandbox_path, output_path)
        os.makedirs(os.path.dirname(full_output), exist_ok=True)
        
        # 保存
        save_kwargs = {}
        if operation == 'compress' or original_format in ('JPEG', None):
            save_kwargs['quality'] = params.get('quality', 85)
            save_kwargs['optimize'] = True
        
        img.save(full_output, **save_kwargs)
        
        final_size = os.path.getsize(full_output)
        
        return {
            "success": True,
            "data": f"Image {operation} completed",
            "input_path": input_path,
            "output_path": output_path,
            "original_size": original_size,
            "new_size": img.size,
            "file_size_bytes": final_size,
            "file_size_kb": round(final_size / 1024, 2)
        }
        
    except Exception as e:
        return {"success": False, "error": f"Image processing failed: {str(e)}"}


def _convert_excel(sandbox_path: str, params: Dict[str, Any]) -> Dict[str, Any]:
    """
    Excel 转换便利操作
    
    Params:
        - input_path: 输入 Excel 路径
        - output_path: 输出路径（可选，默认同名.csv或.json）
        - format: 输出格式 - csv, json, json_records（默认 csv）
        - sheet: 工作表名称或索引（默认 0）
        - preview_only: 仅预览前N行，不保存（默认 False）
        - preview_rows: 预览行数（默认 10）
    """
    try:
        import pandas as pd
    except ImportError:
        return {"success": False, "error": "pandas not available"}
    
    input_path = params.get('input_path')
    output_path = params.get('output_path')
    fmt = params.get('format', 'csv').lower()
    sheet = params.get('sheet', 0)
    preview_only = params.get('preview_only', False)
    preview_rows = params.get('preview_rows', 10)
    
    if not input_path:
        return {"success": False, "error": "Missing input_path parameter"}
    
    try:
        full_input = _validate_path(sandbox_path, input_path)
        if not os.path.exists(full_input):
            return {"success": False, "error": f"Input file not found: {input_path}"}
        
        # 读取 Excel
        df = pd.read_excel(full_input, sheet_name=sheet)
        
        row_count = len(df)
        col_count = len(df.columns)
        columns = list(df.columns)
        
        if preview_only:
            preview = df.head(preview_rows).to_dict(orient='records')
            return {
                "success": True,
                "preview": preview,
                "total_rows": row_count,
                "total_columns": col_count,
                "columns": columns
            }
        
        # 确定输出路径
        if not output_path:
            name, _ = os.path.splitext(input_path)
            output_path = f"{name}.{fmt}"
        
        full_output = _validate_path(sandbox_path, output_path)
        os.makedirs(os.path.dirname(full_output), exist_ok=True)
        
        # 转换并保存
        if fmt == 'csv':
            df.to_csv(full_output, index=False, encoding='utf-8-sig')
        elif fmt in ('json', 'json_records'):
            df.to_json(full_output, orient='records', force_ascii=False, indent=2)
        elif fmt == 'json_lines':
            df.to_json(full_output, orient='records', lines=True, force_ascii=False)
        else:
            return {"success": False, "error": f"Unsupported format: {fmt}"}
        
        return {
            "success": True,
            "data": f"Converted to {fmt}",
            "input_path": input_path,
            "output_path": output_path,
            "rows": row_count,
            "columns": col_count,
            "column_names": columns
        }
        
    except Exception as e:
        return {"success": False, "error": f"Excel conversion failed: {str(e)}"}


def _extract_pdf_text(sandbox_path: str, params: Dict[str, Any]) -> Dict[str, Any]:
    """
    PDF 文本提取便利操作
    
    Params:
        - input_path: PDF 文件路径
        - pages: 页码列表（可选，默认全部）
        - output_path: 输出文本文件路径（可选）
        - max_chars: 返回的最大字符数（可选，默认全部）
    """
    try:
        from PyPDF2 import PdfReader
    except ImportError:
        return {"success": False, "error": "PyPDF2 not available"}
    
    input_path = params.get('input_path')
    pages = params.get('pages')  # None = all pages
    output_path = params.get('output_path')
    max_chars = params.get('max_chars')
    
    if not input_path:
        return {"success": False, "error": "Missing input_path parameter"}
    
    try:
        full_input = _validate_path(sandbox_path, input_path)
        if not os.path.exists(full_input):
            return {"success": False, "error": f"Input file not found: {input_path}"}
        
        reader = PdfReader(full_input)
        total_pages = len(reader.pages)
        
        # 确定要处理的页面
        if pages is None:
            pages = list(range(total_pages))
        else:
            # 转换为0-based索引
            pages = [p - 1 if p > 0 else p for p in pages]
            pages = [p for p in pages if 0 <= p < total_pages]
        
        # 提取文本
        extracted = []
        for page_num in pages:
            page = reader.pages[page_num]
            text = page.extract_text() or ""
            extracted.append({
                'page': page_num + 1,
                'text': text
            })
        
        full_text = "\\n\\n".join(item['text'] for item in extracted)
        
        # 保存到文件
        if output_path:
            full_output = _validate_path(sandbox_path, output_path)
            with open(full_output, 'w', encoding='utf-8') as f:
                f.write(full_text)
        
        # 截断返回的文本
        return_text = full_text
        truncated = False
        if max_chars and len(full_text) > max_chars:
            return_text = full_text[:max_chars] + "\\n...[truncated]"
            truncated = True
        
        return {
            "success": True,
            "data": return_text,
            "total_pages": total_pages,
            "extracted_pages": len(extracted),
            "total_chars": len(full_text),
            "truncated": truncated,
            "output_file": output_path if output_path else None
        }
        
    except Exception as e:
        return {"success": False, "error": f"PDF extraction failed: {str(e)}"}


def _download_file(sandbox_path: str, params: Dict[str, Any]) -> Dict[str, Any]:
    """
    文件下载便利操作
    
    Params:
        - url: 下载链接
        - output_path: 保存路径（可选，默认从URL提取文件名）
        - timeout: 超时秒数（默认 60）
        - headers: 自定义请求头（可选）
    """
    try:
        import requests
    except ImportError:
        return {"success": False, "error": "requests not available"}
    
    url = params.get('url')
    output_path = params.get('output_path')
    timeout = params.get('timeout', 60)
    headers = params.get('headers', {'User-Agent': 'Mozilla/5.0 (compatible; Bot/1.0)'})
    
    if not url:
        return {"success": False, "error": "Missing url parameter"}
    
    try:
        # 确定输出文件名
        if not output_path:
            # 从URL提取文件名
            from urllib.parse import urlparse
            parsed = urlparse(url)
            output_path = os.path.basename(parsed.path) or 'downloaded_file'
        
        full_output = _validate_path(sandbox_path, output_path)
        os.makedirs(os.path.dirname(full_output), exist_ok=True)
        
        # 下载
        response = requests.get(url, headers=headers, timeout=timeout, stream=True)
        response.raise_for_status()
        
        total_size = int(response.headers.get('content-length', 0))
        downloaded = 0
        
        with open(full_output, 'wb') as f:
            for chunk in response.iter_content(chunk_size=8192):
                if chunk:
                    f.write(chunk)
                    downloaded += len(chunk)
        
        return {
            "success": True,
            "data": f"Downloaded successfully",
            "url": url,
            "output_path": output_path,
            "file_size_bytes": downloaded,
            "file_size_kb": round(downloaded / 1024, 2),
            "content_type": response.headers.get('content-type')
        }
        
    except Exception as e:
        return {"success": False, "error": f"Download failed: {str(e)}"}


def _sqlite_query(sandbox_path: str, params: Dict[str, Any]) -> Dict[str, Any]:
    """
    执行 SQLite 查询
    
    Params:
        - db_path: 数据库文件路径（相对沙箱）
        - query: SQL 查询语句
        - params: 查询参数（可选，用于参数化查询）
        - max_rows: 最大返回行数（默认 1000）
    """
    import sqlite3
    
    db_path = params.get('db_path')
    query = params.get('query')
    query_params = params.get('params', ())
    max_rows = params.get('max_rows', 1000)
    
    if not db_path:
        return {"success": False, "error": "Missing db_path parameter"}
    if not query:
        return {"success": False, "error": "Missing query parameter"}
    
    try:
        full_db_path = _validate_path(sandbox_path, db_path)
        if not os.path.exists(full_db_path):
            return {"success": False, "error": f"Database not found: {db_path}"}
        
        conn = sqlite3.connect(full_db_path)
        conn.row_factory = sqlite3.Row
        cursor = conn.cursor()
        
        # 执行查询
        cursor.execute(query, query_params)
        
        # 判断是否为 SELECT 查询
        if query.strip().upper().startswith('SELECT') or query.strip().upper().startswith('PRAGMA'):
            rows = cursor.fetchmany(max_rows)
            columns = [description[0] for description in cursor.description] if cursor.description else []
            
            # 转换为字典列表
            results = []
            for row in rows:
                results.append({key: row[key] for key in row.keys()})
            
            # 检查是否还有更多行（在关闭连接前）
            has_more = cursor.fetchone() is not None
            
            conn.close()
            
            # 生成人类可读的输出
            preview = []
            for i, row in enumerate(results[:3]):
                row_str = ", ".join([f"{k}={v}" for k, v in row.items()])
                preview.append(f"  行{i+1}: {row_str}")
            if len(results) > 3:
                preview.append(f"  ... 还有 {len(results) - 3} 行")
            
            return {
                "success": True,
                "data": results,
                "stdout": f"🗄️ SQLite 查询\n📊 返回 {len(results)} 行\n📋 列: {', '.join(columns)}\n\n📄 预览:\n" + "\n".join(preview) if preview else "(无数据)",
                "columns": columns,
                "row_count": len(results),
                "truncated": has_more
            }
        else:
            # INSERT/UPDATE/DELETE/CREATE 等
            conn.commit()
            affected = cursor.rowcount
            conn.close()
            
            return {
                "success": True,
                "data": f"Query executed successfully",
                "stdout": f"🗄️ SQL 执行成功\n✏️ 影响行数: {affected}",
                "rows_affected": affected
            }
        
    except sqlite3.Error as e:
        # 提供更详细的错误信息
        error_msg = f"SQLite error: {str(e)}"
        # 检查是否是常见的语法错误
        query_stripped = query.strip() if query else ""
        if "LIMIT" in str(e) and "syntax error" in str(e):
            error_msg = f"SQLite 语法错误 (LIMIT): 请检查 LIMIT 关键词前是否有空格\n示例: SELECT * FROM table LIMIT 10\n\n原始错误: {str(e)}"
        elif "syntax error" in str(e):
            # 找出错误位置附近的内容
            error_msg = f"SQL 语法错误: {str(e)}\n\n查询语句:\n{query_stripped[:200]}"
        return {"success": False, "error": error_msg}
    except Exception as e:
        return {"success": False, "error": f"Query failed: {str(e)}\n\n查询语句:\n{query_stripped[:200] if query else '(empty)'}"}


def _sqlite_tables(sandbox_path: str, params: Dict[str, Any]) -> Dict[str, Any]:
    """
    获取 SQLite 数据库的表结构信息
    
    Params:
        - db_path: 数据库文件路径
        - detail: 是否包含列详情（默认 True）
    """
    import sqlite3
    
    db_path = params.get('db_path')
    detail = params.get('detail', True)
    
    if not db_path:
        return {"success": False, "error": "Missing db_path parameter"}
    
    try:
        full_db_path = _validate_path(sandbox_path, db_path)
        if not os.path.exists(full_db_path):
            return {"success": False, "error": f"Database not found: {db_path}"}
        
        conn = sqlite3.connect(full_db_path)
        cursor = conn.cursor()
        
        # 获取所有表
        cursor.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")
        tables = [row[0] for row in cursor.fetchall()]
        
        result = {
            "success": True,
            "db_path": db_path,
            "table_count": len(tables),
            "tables": []
        }
        
        if detail:
            for table_name in tables:
                # 获取表结构
                cursor.execute(f"PRAGMA table_info({table_name})")
                columns = []
                for row in cursor.fetchall():
                    columns.append({
                        "name": row[1],
                        "type": row[2],
                        "notnull": bool(row[3]),
                        "default": row[4],
                        "pk": bool(row[5])
                    })
                
                # 获取行数
                cursor.execute(f"SELECT COUNT(*) FROM {table_name}")
                row_count = cursor.fetchone()[0]
                
                result["tables"].append({
                    "name": table_name,
                    "row_count": row_count,
                    "columns": columns
                })
        else:
            result["tables"] = [{"name": name} for name in tables]
        
        conn.close()
        return result
        
    except sqlite3.Error as e:
        return {"success": False, "error": f"SQLite error: {str(e)}"}
    except Exception as e:
        return {"success": False, "error": f"Failed to get tables: {str(e)}"}


def _git_init(sandbox_path: str, params: Dict[str, Any]) -> Dict[str, Any]:
    """
    初始化 Git 仓库
    
    Params:
        - path: 仓库路径（相对沙箱，默认当前目录）
    """
    try:
        from dulwich.repo import Repo
    except ImportError:
        return {"success": False, "error": "dulwich not available"}
    
    repo_path = params.get('path', '.')
    
    try:
        full_path = _validate_path(sandbox_path, repo_path)
        
        # 检查是否已存在仓库
        git_dir = os.path.join(full_path, '.git')
        if os.path.exists(git_dir):
            return {"success": False, "error": "Git repository already exists"}
        
        # 初始化仓库
        Repo.init(full_path)
        
        return {
            "success": True,
            "data": f"Git repository initialized at {repo_path}",
            "stdout": f"📁 Git 仓库已初始化\n📂 路径: {repo_path}",
            "path": repo_path
        }
        
    except Exception as e:
        return {"success": False, "error": f"Git init failed: {str(e)}"}


def _git_status(sandbox_path: str, params: Dict[str, Any]) -> Dict[str, Any]:
    """
    获取 Git 仓库状态
    
    Params:
        - path: 仓库路径（相对沙箱，默认当前目录）
    """
    try:
        from dulwich.repo import Repo
        from dulwich.porcelain import status
    except ImportError:
        return {"success": False, "error": "dulwich not available"}
    
    repo_path = params.get('path', '.')
    
    try:
        full_path = _validate_path(sandbox_path, repo_path)
        repo = Repo(full_path)
        
        # 获取状态
        status_result = status(repo)
        
        # 解析状态
        staged = {
            'add': list(status_result.staged['add']),
            'delete': list(status_result.staged['delete']),
            'modify': list(status_result.staged['modify'])
        }
        
        unstaged = list(status_result.unstaged)
        untracked = list(status_result.untracked)
        
        staged_count = len(staged['add']) + len(staged['delete']) + len(staged['modify'])
        unstaged_count = len(unstaged)
        untracked_count = len(untracked)
        
        return {
            "success": True,
            "stdout": f"📁 Git 仓库状态\n📦 暂存区: {staged_count} 个\n📄 未暂存: {unstaged_count} 个\n❓ 未跟踪: {untracked_count} 个\n{'✅ 工作区干净' if not any([staged['add'], staged['delete'], staged['modify'], unstaged, untracked]) else '⚠️  有变更'}",
            "staged": staged,
            "unstaged": unstaged,
            "untracked": untracked,
            "is_clean": not any([staged['add'], staged['delete'], staged['modify'], unstaged, untracked])
        }
        
    except Exception as e:
        return {"success": False, "error": f"Git status failed: {str(e)}"}


def _git_log(sandbox_path: str, params: Dict[str, Any]) -> Dict[str, Any]:
    """
    获取 Git 提交历史
    
    Params:
        - path: 仓库路径（相对沙箱，默认当前目录）
        - max_count: 最大返回提交数（默认 20）
    """
    try:
        from dulwich.repo import Repo
        from dulwich.walk import Walker
    except ImportError:
        return {"success": False, "error": "dulwich not available"}
    
    repo_path = params.get('path', '.')
    max_count = params.get('max_count', 20)
    
    try:
        full_path = _validate_path(sandbox_path, repo_path)
        repo = Repo(full_path)
        
        commits = []
        walker = Walker(repo, repo.head(), max_entries=max_count)
        
        for entry in walker:
            commit = entry.commit
            # 获取 commit SHA
            commit_sha = commit.id
            commits.append({
                "sha": commit_sha.hex()[:7],
                "full_sha": commit_sha.hex(),
                "message": commit.message.decode('utf-8', errors='replace').strip(),
                "author": commit.author.decode('utf-8', errors='replace'),
                "timestamp": commit.commit_time,
                "committer": commit.committer.decode('utf-8', errors='replace')
            })
        
        # 生成人类可读的输出
        if commits:
            commit_lines = [f"📌 {c['sha']} - {c['message'][:50]}{'...' if len(c['message']) > 50 else ''}" for c in commits[:5]]
            if len(commits) > 5:
                commit_lines.append(f"  ... 还有 {len(commits) - 5} 个提交")
            stdout = f"📜 Git 提交历史\n📊 共 {len(commits)} 个提交\n\n" + "\n".join(commit_lines)
        else:
            stdout = "📜 Git 提交历史\n(无提交记录)"
        
        return {
            "success": True,
            "stdout": stdout,
            "commits": commits,
            "count": len(commits)
        }
        
    except Exception as e:
        return {"success": False, "error": f"Git log failed: {str(e)}"}


def _git_diff(sandbox_path: str, params: Dict[str, Any]) -> Dict[str, Any]:
    """
    获取Git差异 - 支持staged、unstaged和cached模式
    
    Params:
        - path: 仓库路径（相对沙箱，默认当前目录）
        - file_path: 指定文件路径（可选）
        - staged: 比较HEAD和索引（默认False，比较索引和工作区）
        - cached: 同staged，git标准参数别名
    """
    try:
        from dulwich.repo import Repo
        from dulwich.diff_tree import tree_changes
    except ImportError:
        return {"success": False, "error": "dulwich not available"}
    
    repo_path = params.get('path', '.')
    file_path = params.get('file_path')
    staged = params.get('staged', False) or params.get('cached', False)
    
    try:
        full_path = _validate_path(sandbox_path, repo_path)
        repo = Repo(full_path)
        
        # 检查是否有提交
        try:
            head_commit = repo[repo.head()]
            head_tree = head_commit.tree
        except:
            return {"success": True, "data": "Empty repository - no commits yet", "files": []}
        
        if staged:
            # staged模式：比较 HEAD 和 索引
            return _diff_staged(repo, head_tree, file_path)
        else:
            # unstaged模式：比较 索引 和 工作区
            return _diff_unstaged(repo, full_path, head_tree, file_path)
        
    except Exception as e:
        import traceback
        return {"success": False, "error": f"Git diff failed: {str(e)}", "traceback": traceback.format_exc()}


def _diff_staged(repo, head_tree, file_path_filter=None):
    """比较 HEAD 和 索引（staged changes）"""
    from dulwich.diff_tree import tree_changes
    
    # 获取索引树
    index = repo.open_index()
    index_tree_id = index.commit(repo.object_store)
    
    # 比较 HEAD 和 索引
    changes = list(tree_changes(repo, head_tree, index_tree_id))
    
    diffs = []
    for change in changes:
        old_path, new_path = _get_change_paths(change)
        
        # 过滤指定文件
        if file_path_filter and old_path != file_path_filter and new_path != file_path_filter:
            continue
        
        diffs.append({
            "change_type": _normalize_change_type(change.type),
            "old_path": old_path,
            "new_path": new_path,
            "staged": True
        })
    
    # 生成人类可读的输出
    if diffs:
        file_lines = [f"  {_normalize_change_type(change.type)}: {new_path or old_path}" for change in changes]
        stdout = f"📊 Staged 变更 ({len(diffs)} 个文件)\n" + "\n".join(file_lines)
    else:
        stdout = "📊 Staged 变更\n(无变更)"
    
    return {
        "success": True,
        "stdout": stdout,
        "files": diffs,
        "count": len(diffs),
        "mode": "staged"
    }


def _diff_unstaged(repo, repo_path, head_tree, file_path_filter=None):
    """
    比较 索引 和 工作区（unstaged changes）
    简化实现：遍历索引和工作区，对比SHA
    """
    index = repo.open_index()
    diffs = []
    
    # 1. 检查索引中的文件（修改和删除）
    for path_bytes, entry in index.items():
        path = path_bytes.decode('utf-8', errors='replace')
        
        # 过滤指定文件
        if file_path_filter and path != file_path_filter:
            continue
        
        full_path = os.path.join(repo_path, path)
        
        if not os.path.exists(full_path):
            # 文件在索引中但不存在于工作区 -> 删除
            diffs.append({
                "change_type": "delete",
                "old_path": path,
                "new_path": None,
                "staged": False
            })
        else:
            # 检查文件是否修改
            current_sha = _get_file_sha(full_path)
            if current_sha != entry.sha.hex():
                diffs.append({
                    "change_type": "modify",
                    "old_path": path,
                    "new_path": path,
                    "staged": False
                })
    
    # 2. 检查工作区中的新文件
    for root, dirs, files in os.walk(repo_path):
        if '.git' in root:
            continue
        
        for file in files:
            rel_path = os.path.relpath(os.path.join(root, file), repo_path)
            path_bytes = rel_path.encode('utf-8')
            
            # 过滤指定文件
            if file_path_filter and rel_path != file_path_filter:
                continue
            
            # 文件在工作区但不在索引中 -> 新增
            if path_bytes not in index:
                diffs.append({
                    "change_type": "add",
                    "old_path": None,
                    "new_path": rel_path,
                    "staged": False
                })
    
    # 生成人类可读的输出
    if diffs:
        file_lines = []
        for d in diffs[:10]:
            icon = {"add": "➕", "delete": "🗑️", "modify": "✏️"}.get(d["change_type"], "📝")
            path = d["new_path"] or d["old_path"]
            file_lines.append(f"  {icon} {path}")
        if len(diffs) > 10:
            file_lines.append(f"  ... 还有 {len(diffs) - 10} 个文件")
        stdout = f"📊 Unstaged 变更 ({len(diffs)} 个文件)\n" + "\n".join(file_lines)
    else:
        stdout = "📊 Unstaged 变更\n(无变更)"
    
    return {
        "success": True,
        "stdout": stdout,
        "files": diffs,
        "count": len(diffs),
        "mode": "unstaged"
    }


def _get_file_sha(file_path):
    """计算文件的SHA1哈希（Git风格）"""
    from hashlib import sha1
    
    try:
        with open(file_path, 'rb') as f:
            content = f.read()
        
        # Git风格的SHA：'blob <size>\0<content>'
        header = f"blob {len(content)}\0".encode()
        return sha1(header + content).hexdigest()
    except:
        return None


def _get_change_paths(change):
    """安全获取变更路径"""
    old_path = None
    new_path = None
    
    if change.old and hasattr(change.old, 'path') and change.old.path:
        old_path = change.old.path.decode('utf-8', errors='replace')
    if change.new and hasattr(change.new, 'path') and change.new.path:
        new_path = change.new.path.decode('utf-8', errors='replace')
    
    return old_path, new_path


def _normalize_change_type(change_type):
    """标准化变更类型"""
    # dulwich的change type: 'add', 'delete', 'modify', 'unchanged'
    if change_type == 'add':
        return 'add'
    elif change_type == 'delete':
        return 'delete'
    elif change_type == 'modify':
        return 'modify'
    else:
        return change_type


def _git_add(sandbox_path: str, params: Dict[str, Any]) -> Dict[str, Any]:
    """
    Add files to Git staging area
    
    Params:
        - path: Repository path (relative to sandbox, default current dir)
        - file_path: File path (relative to repo), supports wildcards like '*.txt', or '.' for all
    """
    try:
        from dulwich.repo import Repo
        from dulwich.porcelain import add
    except ImportError:
        return {"success": False, "error": "dulwich not available"}
    
    repo_path = params.get('path', '.')
    file_path = params.get('file_path')
    
    if not file_path:
        return {"success": False, "error": "Missing file_path parameter"}
    
    try:
        full_path = _validate_path(sandbox_path, repo_path)
        repo = Repo(full_path)
        
        import fnmatch
        full_file_path = _validate_path(full_path, file_path)
        
        files_to_add = []
        if file_path == '.':
            for root, dirs, files in os.walk(full_path):
                if '.git' in root:
                    continue
                for file in files:
                    if file == '.git':
                        continue
                    rel_path = os.path.relpath(os.path.join(root, file), full_path)
                    files_to_add.append(rel_path.encode('utf-8'))
        elif '*' in file_path or '?' in file_path:
            for root, dirs, files in os.walk(full_path):
                if '.git' in root:
                    continue
                for file in files:
                    rel_path = os.path.relpath(os.path.join(root, file), full_path)
                    if fnmatch.fnmatch(rel_path, file_path):
                        files_to_add.append(rel_path.encode('utf-8'))
        else:
            if os.path.exists(full_file_path):
                rel_path = os.path.relpath(full_file_path, full_path)
                files_to_add.append(rel_path.encode('utf-8'))
            else:
                return {"success": False, "error": f"File not found: {file_path}"}
        
        if not files_to_add:
            return {
                "success": True,
                "data": "No files to add",
                "stdout": "➕ Git 添加\n📊 没有文件需要添加",
                "files_added": []
            }
        
        add(repo, files_to_add)
        
        added_files = [f.decode('utf-8', errors='replace') for f in files_to_add]
        return {
            "success": True,
            "data": f"Added {len(files_to_add)} file(s) to staging area",
            "stdout": f"➕ Git 添加\n📊 添加了 {len(added_files)} 个文件\n  " + "\n  ".join(added_files[:10]) + (f"\n  ... 还有 {len(added_files) - 10} 个文件" if len(added_files) > 10 else ""),
            "files_added": added_files
        }
        
    except Exception as e:
        return {"success": False, "error": f"Git add failed: {str(e)}"}


def _git_commit(sandbox_path: str, params: Dict[str, Any]) -> Dict[str, Any]:
    """
    Commit staged changes
    
    Params:
        - path: Repository path (relative to sandbox, default current dir)
        - message: Commit message (required)
        - author_name: Author name (optional, default "RikkaHub User")
        - author_email: Author email (optional, default "user@rikkahub.local")
    """
    try:
        from dulwich.repo import Repo
        from dulwich.porcelain import commit
    except ImportError:
        return {"success": False, "error": "dulwich not available"}
    
    repo_path = params.get('path', '.')
    message = params.get('message')
    author_name = params.get('author_name', 'RikkaHub User')
    author_email = params.get('author_email', 'user@rikkahub.local')
    
    if not message:
        return {"success": False, "error": "Missing message parameter"}
    
    try:
        full_path = _validate_path(sandbox_path, repo_path)
        repo = Repo(full_path)
        
        from dulwich.porcelain import status
        status_result = status(repo)
        if not any([status_result.staged['add'], status_result.staged['delete'], status_result.staged['modify']]):
            return {
                "success": False,
                "error": "No changes staged for commit",
                "stdout": "❌ Git 提交失败\n📝 没有暂存的变更需要提交"
            }
        
        author = f"{author_name} <{author_email}>".encode('utf-8')
        
        commit_sha = commit(
            repo,
            message=message.encode('utf-8'),
            author=author,
            committer=author
        )
        
        short_sha = commit_sha.hex()[:7]
        return {
            "success": True,
            "data": f"Committed: {message}",
            "stdout": f"✅ Git 提交成功\n📝 提交消息: {message}\n🔖 提交 SHA: {short_sha}\n👤 作者: {author_name}",
            "commit_sha": short_sha,
            "full_sha": commit_sha.hex(),
            "author": f"{author_name} <{author_email}>"
        }
        
    except Exception as e:
        return {"success": False, "error": f"Git commit failed: {str(e)}"}


def _checkout_file(repo, repo_path: str, file_path: str) -> Dict[str, Any]:
    """从HEAD恢复特定文件"""
    try:
        # 获取HEAD提交
        head_sha = repo.head()
        commit = repo[head_sha]
        tree = repo[commit.tree]
        
        # 查找文件
        file_path_bytes = file_path.encode('utf-8')
        if file_path_bytes not in tree:
            return {"success": False, "error": f"File '{file_path}' not found in HEAD"}
        
        # 获取文件内容
        entry = tree[file_path_bytes]
        blob = repo[entry.sha]
        content = blob.as_raw_string()
        
        # 写入文件
        full_file_path = os.path.join(repo_path, file_path)
        os.makedirs(os.path.dirname(full_file_path) or '.', exist_ok=True)
        with open(full_file_path, 'wb') as f:
            f.write(content)
        
        return {
            "success": True,
            "data": f"Restored '{file_path}' from HEAD",
            "stdout": f"📄 Git 文件恢复\n✅ 已恢复文件: {file_path}\n📏 大小: {len(content)} bytes",
            "file_path": file_path,
            "bytes_written": len(content)
        }
        
    except Exception as e:
        return {"success": False, "error": f"File checkout failed: {str(e)}"}


def _checkout_branch(repo, repo_path: str, branch_name: str, create: bool) -> Dict[str, Any]:
    """切换到指定分支，完全重置工作区"""
    from dulwich.index import build_index_from_tree
    
    branch_ref = f"refs/heads/{branch_name}".encode('utf-8')
    
    # 检查分支是否存在
    if branch_ref not in repo.refs:
        if create:
            # 创建新分支
            try:
                head_sha = repo.head()
                repo.refs[branch_ref] = head_sha
            except Exception as e:
                return {"success": False, "error": f"Cannot create branch: {str(e)}"}
        else:
            return {"success": False, "error": f"Branch '{branch_name}' not found"}
    
    # 获取目标分支的commit和tree
    target_sha = repo.refs[branch_ref]
    commit = repo[target_sha]
    tree_sha = commit.tree
    
    # 切换HEAD
    repo.refs.set_symbolic_ref(b'HEAD', branch_ref)
    
    # 重置索引：使用build_index_from_tree重建索引
    index_path = os.path.join(repo_path, '.git', 'index')
    build_index_from_tree(repo.path, index_path, repo.object_store, tree_sha)
    
    # 重置工作区：遍历tree，将每个blob写入文件系统
    _reset_working_tree(repo, repo_path, tree_sha)
    
    # 删除目标分支没有但工作区有的文件
    _remove_untracked_files(repo, repo_path, tree_sha)
    
    action_msg = "创建并切换到" if create else "切换到"
    return {
        "success": True,
        "data": f"{action_msg} branch '{branch_name}' and reset working tree",
        "stdout": f"🌿 Git 分支切换\n✅ 已{action_msg}: {branch_name}\n🔖 SHA: {target_sha.hex()[:7]}\n📊 工作区已重置",
        "branch": branch_name,
        "commit_sha": target_sha.hex()[:7],
        "files_reset": True
    }


def _reset_working_tree(repo, repo_path: str, tree_sha):
    """根据tree对象重置工作区文件"""
    tree = repo[tree_sha]
    
    for entry in tree.items():
        path = entry.path.decode('utf-8', errors='replace')
        full_path = os.path.join(repo_path, path)
        
        if entry.mode == 0o040000:  # 目录
            os.makedirs(full_path, exist_ok=True)
        else:  # 文件
            # 获取blob内容
            blob = repo[entry.sha]
            content = blob.as_raw_string()
            
            # 确保目录存在
            os.makedirs(os.path.dirname(full_path) or '.', exist_ok=True)
            
            # 写入文件
            with open(full_path, 'wb') as f:
                f.write(content)


def _remove_untracked_files(repo, repo_path: str, tree_sha):
    """删除工作区中但不在tree中的文件"""
    # 获取tree中所有文件路径
    tree = repo[tree_sha]
    tracked_files = set()
    
    def collect_paths(tree_obj, prefix=''):
        for entry in tree_obj.items():
            path = os.path.join(prefix, entry.path.decode('utf-8', errors='replace'))
            if entry.mode == 0o040000:  # 目录
                sub_tree = repo[entry.sha]
                collect_paths(sub_tree, path)
            else:
                tracked_files.add(path)
    
    collect_paths(tree)
    
    # 遍历工作区，删除未跟踪的文件
    for root, dirs, files in os.walk(repo_path):
        # 跳过.git目录
        if '.git' in root:
            continue
        
        for file in files:
            rel_path = os.path.relpath(os.path.join(root, file), repo_path)
            if rel_path not in tracked_files:
                try:
                    os.remove(os.path.join(root, file))
                except:
                    pass


def _git_branch(sandbox_path: str, params: Dict[str, Any]) -> Dict[str, Any]:
    """
    Branch management: list, create, delete branches
    
    Params:
        - path: Repository path (relative to sandbox, default current dir)
        - action: Operation type - "list", "create", "delete" (default "list")
        - branch_name: Branch name (required for create/delete)
        - checkout: Switch to new branch after creation (optional, default False)
    """
    try:
        from dulwich.repo import Repo
    except ImportError:
        return {"success": False, "error": "dulwich not available"}
    
    repo_path = params.get('path', '.')
    action = params.get('action', 'list')
    branch_name = params.get('branch_name')
    checkout = params.get('checkout', False)
    
    try:
        full_path = _validate_path(sandbox_path, repo_path)
        repo = Repo(full_path)
        
        if action == 'list':
            branches = []
            current_branch = None
            
            try:
                current_ref = repo.refs.read_ref(b'HEAD')
                if current_ref.startswith(b'ref: refs/heads/'):
                    current_branch = current_ref[16:].decode('utf-8', errors='replace')
            except:
                pass
            
            for ref in repo.refs.keys():
                if ref.startswith(b'refs/heads/'):
                    name = ref[11:].decode('utf-8', errors='replace')
                    sha = repo.refs[ref].hex()[:7]
                    branches.append({
                        "name": name,
                        "sha": sha,
                        "is_current": name == current_branch
                    })
            
            # 构建stdout
            branch_lines = []
            for b in branches:
                icon = "👉" if b["name"] == current_branch else "  "
                branch_lines.append(f"{icon} {b['name']} ({b['sha']})")
            
            return {
                "success": True,
                "stdout": f"🌿 Git 分支列表\n📊 共 {len(branches)} 个分支\n" + ("\n".join(branch_lines) if branch_lines else "  (无分支)"),
                "branches": branches,
                "current_branch": current_branch,
                "count": len(branches)
            }
        
        elif action == 'create':
            if not branch_name:
                return {"success": False, "error": "Missing branch_name parameter"}
            
            branch_ref = f"refs/heads/{branch_name}".encode('utf-8')
            
            if branch_ref in repo.refs:
                return {"success": False, "error": f"Branch '{branch_name}' already exists"}
            
            try:
                head_sha = repo.head()
                repo.refs[branch_ref] = head_sha
            except Exception as e:
                return {"success": False, "error": f"Cannot create branch: {str(e)}"}
            
            if checkout:
                # 使用完整的checkout逻辑重置工作区
                return _checkout_branch(repo, full_path, branch_name, create=False)
            
            return {
                "success": True,
                "data": f"Created branch '{branch_name}'",
                "stdout": f"✅ Git 创建分支成功\n🌿 分支名: {branch_name}",
                "branch": branch_name
            }
        
        elif action == 'checkout':
            # 直接调用checkout逻辑
            if not branch_name:
                return {"success": False, "error": "Missing branch_name parameter"}
            return _checkout_branch(repo, full_path, branch_name, create=False)
        
        elif action == 'delete':
            if not branch_name:
                return {"success": False, "error": "Missing branch_name parameter"}
            
            branch_ref = f"refs/heads/{branch_name}".encode('utf-8')
            
            if branch_ref not in repo.refs:
                return {"success": False, "error": f"Branch '{branch_name}' not found"}
            
            current_ref = repo.refs.read_ref(b'HEAD')
            if current_ref == b'ref: ' + branch_ref:
                return {"success": False, "error": f"Cannot delete current branch '{branch_name}'"}
            
            del repo.refs[branch_ref]
            
            return {
                "success": True,
                "data": f"Deleted branch '{branch_name}'",
                "stdout": f"✅ Git 删除分支成功\n🗑️ 已删除分支: {branch_name}",
                "branch_deleted": branch_name
            }
        
        else:
            return {"success": False, "error": f"Unknown action: {action}"}
        
    except Exception as e:
        return {"success": False, "error": f"Git branch failed: {str(e)}"}


def _git_checkout(sandbox_path: str, params: Dict[str, Any]) -> Dict[str, Any]:
    """
    Switch branches or restore files with full working tree reset
    
    Params:
        - path: Repository path (relative to sandbox, default current dir)
        - branch_name: Branch name (alternative to file_path)
        - file_path: Restore specific file (alternative to branch_name)
        - create: Create branch if not exists (optional, default False, like git checkout -b)
    """
    try:
        from dulwich.repo import Repo
        from dulwich.objects import Tree
    except ImportError:
        return {"success": False, "error": "dulwich not available"}
    
    repo_path = params.get('path', '.')
    branch_name = params.get('branch_name')
    file_path = params.get('file_path')
    create = params.get('create', False)
    
    if not branch_name and not file_path:
        return {"success": False, "error": "Must specify branch_name or file_path"}
    
    try:
        full_path = _validate_path(sandbox_path, repo_path)
        repo = Repo(full_path)
        
        # 场景1: 恢复特定文件
        if file_path and not branch_name:
            return _checkout_file(repo, full_path, file_path)
        
        # 场景2: 切换分支
        return _checkout_branch(repo, full_path, branch_name, create)
        
    except Exception as e:
        import traceback
        return {"success": False, "error": f"Git checkout failed: {str(e)}", "traceback": traceback.format_exc()}


def _git_rm(sandbox_path: str, params: Dict[str, Any]) -> Dict[str, Any]:
    """
    Remove files from Git staging area (git rm)
    
    Params:
        - path: Repository path (relative to sandbox, default current dir)
        - file_path: File path to remove
        - cached: Only remove from staging, keep file (optional, default False)
    """
    try:
        from dulwich.repo import Repo
        from dulwich.index import Index
    except ImportError:
        return {"success": False, "error": "dulwich not available"}
    
    repo_path = params.get('path', '.')
    file_path = params.get('file_path')
    cached = params.get('cached', False)
    
    if not file_path:
        return {"success": False, "error": "Missing file_path parameter"}
    
    try:
        full_path = _validate_path(sandbox_path, repo_path)
        repo = Repo(full_path)
        
        full_file_path = _validate_path(full_path, file_path)
        rel_path = os.path.relpath(full_file_path, full_path)
        
        index = repo.open_index()
        path_bytes = rel_path.encode('utf-8')
        
        if path_bytes in index:
            del index[path_bytes]
        
        # Write the index back
        index.write()
        
        # If not cached, also remove the file from disk
        file_deleted = False
        if not cached and os.path.exists(full_file_path):
            os.remove(full_file_path)
            file_deleted = True
        
        action = "从暂存区移除" if cached else "删除"
        return {
            "success": True,
            "stdout": f"🗑️ Git 删除\n✅ 已{action}: {file_path}" + ("\n📄 文件已从磁盘删除" if file_deleted else "\n📄 文件仍保留在磁盘")
        }
    except Exception as e:
        return {"success": False, "error": str(e)}


def _git_checkpoint(sandbox_path: str, params: Dict[str, Any]) -> Dict[str, Any]:
    """
    Create a workflow checkpoint (git commit with special message)
    
    This operation stages all changes and creates a commit with a special prefix
    to identify it as a workflow checkpoint.
    
    Params:
        - path: Repository path (relative to sandbox, default current dir)
        - message: Checkpoint message (optional, default "Workflow checkpoint")
        - bound_message_index: Index of the message this checkpoint is bound to (optional)
    
    Returns:
        - success: True if checkpoint created
        - checkpoint_id: Git commit hash (short)
        - full_sha: Full git commit hash
        - bound_message_index: Message index (if provided)
    """
    try:
        from dulwich.repo import Repo
        from dulwich.porcelain import commit, status
    except ImportError:
        return {"success": False, "error": "dulwich not available"}
    
    repo_path = params.get('path', '.')
    message = params.get('message', 'Workflow checkpoint')
    bound_message_index = params.get('bound_message_index')
    
    try:
        full_path = _validate_path(sandbox_path, repo_path)
        repo = Repo(full_path)
        
        # Stage all changes
        from dulwich.porcelain import add
        add(repo, paths=['.'])
        
        # Check if there are changes to commit
        status_result = status(repo)
        has_changes = any([
            status_result.staged.get('add', []),
            status_result.staged.get('delete', []),
            status_result.staged.get('modify', [])
        ])
        
        # Build checkpoint message with metadata
        checkpoint_message = f"[WORKFLOW-CHECKPOINT] {message}"
        if bound_message_index is not None:
            checkpoint_message += f" | message_index={bound_message_index}"
        
        author = "RikkaHub Workflow <workflow@rikkahub.local>".encode('utf-8')
        
        if has_changes:
            commit_sha = commit(
                repo,
                message=checkpoint_message.encode('utf-8'),
                author=author,
                committer=author
            )
        else:
            # No changes, just get current HEAD
            commit_sha = repo.head()
        
        short_sha = commit_sha.hex()[:7]
        return {
            "success": True,
            "stdout": f"🎯 Git 检查点\n✅ 已创建检查点\n📝 消息: {message}\n🔖 SHA: {short_sha}" + (f"\n📎 绑定消息索引: {bound_message_index}" if bound_message_index is not None else "") + ("\n⚠️ 没有变更，使用当前 HEAD" if not has_changes else ""),
            "checkpoint_id": short_sha,
            "full_sha": commit_sha.hex(),
            "message": checkpoint_message,
            "bound_message_index": bound_message_index,
            "has_changes": has_changes
        }
        
    except Exception as e:
        import traceback
        return {
            "success": False,
            "error": f"Git checkpoint failed: {str(e)}",
            "traceback": traceback.format_exc()
        }


def _git_restore(sandbox_path: str, params: Dict[str, Any]) -> Dict[str, Any]:
    """
    Restore to a previous checkpoint (git reset --hard)
    
    This operation resets the working directory and index to the specified commit.
    
    Params:
        - path: Repository path (relative to sandbox, default current dir)
        - checkpoint_id: Git commit hash (short or full) to restore to
        - clean: Remove untracked files after restore (optional, default True)
    
    Returns:
        - success: True if restore successful
        - restored_to: Git commit hash that was restored
    """
    try:
        from dulwich.repo import Repo
        from dulwich.objects import Commit, Tree
    except ImportError:
        return {"success": False, "error": "dulwich not available"}
    
    repo_path = params.get('path', '.')
    checkpoint_id = params.get('checkpoint_id')
    clean = params.get('clean', True)
    
    if not checkpoint_id:
        return {"success": False, "error": "Missing checkpoint_id parameter"}
    
    try:
        full_path = _validate_path(sandbox_path, repo_path)
        repo = Repo(full_path)
        
        # Resolve checkpoint_id (handle short hashes)
        try:
            # Try as full SHA first
            target_sha = bytes.fromhex(checkpoint_id)
            if target_sha not in repo.object_store:
                # Try as short hash
                for obj_id in repo.object_store:
                    if obj_id.hex().startswith(checkpoint_id.lower()):
                        target_sha = obj_id
                        break
                else:
                    return {"success": False, "error": f"Checkpoint not found: {checkpoint_id}"}
        except ValueError:
            return {"success": False, "error": f"Invalid checkpoint ID: {checkpoint_id}"}
        
        # Verify it's a commit
        try:
            commit_obj = repo[target_sha]
            if not isinstance(commit_obj, Commit):
                return {"success": False, "error": f"Not a commit: {checkpoint_id}"}
        except KeyError:
            return {"success": False, "error": f"Checkpoint not found: {checkpoint_id}"}
        
        # Reset HEAD to target commit
        repo.refs[b'HEAD'] = target_sha
        
        # Reset working directory
        tree = repo[commit_obj.tree]
        
        # Remove all tracked files
        for root, dirs, files in os.walk(full_path):
            # Skip .git directory
            if '.git' in root:
                continue
            
            for file in files:
                rel_path = os.path.relpath(os.path.join(root, file), full_path)
                path_bytes = rel_path.encode('utf-8')
                
                # Check if file is in the new tree
                try:
                    if path_bytes not in tree:
                        # File not in new tree, delete it
                        os.remove(os.path.join(root, file))
                except:
                    pass
        
        # Write files from new tree
        def write_tree_to_path(tree_obj, base_path):
            for name, entry in tree_obj.items():
                entry_path = os.path.join(base_path, name.decode('utf-8'))
                obj = repo[entry.sha]
                
                if isinstance(obj, Tree):
                    os.makedirs(entry_path, exist_ok=True)
                    write_tree_to_path(obj, entry_path)
                else:
                    # Blob - write file
                    os.makedirs(os.path.dirname(entry_path), exist_ok=True)
                    with open(entry_path, 'wb') as f:
                        f.write(obj.as_raw_string())
        
        write_tree_to_path(tree, full_path)
        
        # Clean untracked files if requested
        if clean:
            # Get tracked files from new tree
            tracked_files = set()
            def collect_tracked(tree_obj, prefix=''):
                for name, entry in tree_obj.items():
                    path = prefix + name.decode('utf-8')
                    tracked_files.add(path)
                    obj = repo[entry.sha]
                    if isinstance(obj, Tree):
                        collect_tracked(obj, path + '/')
            collect_tracked(tree)
            
            # Remove untracked files
            for root, dirs, files in os.walk(full_path):
                if '.git' in root:
                    continue
                
                for file in files:
                    rel_path = os.path.relpath(os.path.join(root, file), full_path)
                    if rel_path not in tracked_files:
                        try:
                            os.remove(os.path.join(root, file))
                        except:
                            pass
        
        return {
            "success": True,
            "stdout": f"🔄 Git 恢复\n✅ 已恢复到检查点\n🔖 SHA: {target_sha.hex()[:7]}" + ("\n🧹 已清理未跟踪文件" if clean else ""),
            "restored_to": checkpoint_id,
            "full_sha": target_sha.hex(),
            "clean": clean
        }
        
    except Exception as e:
        import traceback
        return {
            "success": False,
            "error": f"Git restore failed: {str(e)}",
            "traceback": traceback.format_exc()
        }


def _git_list_checkpoints(sandbox_path: str, params: Dict[str, Any]) -> Dict[str, Any]:
    """
    List all workflow checkpoints
    
    This operation lists all commits with the [WORKFLOW-CHECKPOINT] prefix.
    
    Params:
        - path: Repository path (relative to sandbox, default current dir)
        - limit: Maximum number of checkpoints to return (optional, default 50)
    
    Returns:
        - success: True if listing successful
        - checkpoints: List of checkpoint objects
    """
    try:
        from dulwich.repo import Repo
    except ImportError:
        return {"success": False, "error": "dulwich not available"}
    
    repo_path = params.get('path', '.')
    limit = params.get('limit', 50)
    
    try:
        full_path = _validate_path(sandbox_path, repo_path)
        repo = Repo(full_path)
        
        # Walk commit history
        checkpoints = []
        seen = set()
        walker = repo.get_walker(max_entries=limit * 2)  # Get more to filter
        
        for entry in walker:
            commit = entry.commit
            sha = commit.id.hex()
            
            if sha in seen:
                continue
            seen.add(sha)
            
            message = commit.message.decode('utf-8', errors='replace')
            
            # Check if it's a workflow checkpoint
            if '[WORKFLOW-CHECKPOINT]' in message:
                # Parse metadata
                bound_index = None
                if 'message_index=' in message:
                    try:
                        idx_part = message.split('message_index=')[1].split()[0]
                        bound_index = int(idx_part)
                    except:
                        pass
                
                # Extract clean message
                clean_message = message.replace('[WORKFLOW-CHECKPOINT]', '').strip()
                if '|' in clean_message:
                    clean_message = clean_message.split('|')[0].strip()
                
                checkpoints.append({
                    "checkpoint_id": sha[:7],
                    "full_sha": sha,
                    "message": clean_message,
                    "bound_message_index": bound_index,
                    "timestamp": commit.commit_time
                })
                
                if len(checkpoints) >= limit:
                    break
        
        # 构建stdout
        checkpoint_lines = []
        for cp in checkpoints[:10]:
            time_str = f" ({cp['timestamp']})" if cp['timestamp'] else ""
            bound_info = f" [msg#{cp['bound_message_index']}]" if cp['bound_message_index'] is not None else ""
            checkpoint_lines.append(f"  🎯 {cp['checkpoint_id']}{time_str} - {cp['message']}{bound_info}")
        if len(checkpoints) > 10:
            checkpoint_lines.append(f"  ... 还有 {len(checkpoints) - 10} 个检查点")
        
        return {
            "success": True,
            "stdout": f"📋 Git 检查点列表\n📊 共 {len(checkpoints)} 个检查点\n" + ("\n".join(checkpoint_lines) if checkpoint_lines else "  (无检查点)"),
            "checkpoints": checkpoints,
            "count": len(checkpoints)
        }
        
    except Exception as e:
        import traceback
        return {
            "success": False,
            "error": f"Git list checkpoints failed: {str(e)}",
            "traceback": traceback.format_exc()
        }


def _git_rm(sandbox_path: str, params: Dict[str, Any]) -> Dict[str, Any]:
    """
    Remove files from Git staging area (git rm)
    
    Params:
        - path: Repository path (relative to sandbox, default current dir)
        - file_path: File path to remove
        - cached: Only remove from staging, keep file (optional, default False)
    """
    try:
        from dulwich.repo import Repo
        from dulwich.index import Index
    except ImportError:
        return {"success": False, "error": "dulwich not available"}
    
    repo_path = params.get('path', '.')
    file_path = params.get('file_path')
    cached = params.get('cached', False)
    
    if not file_path:
        return {"success": False, "error": "Missing file_path parameter"}
    
    try:
        full_path = _validate_path(sandbox_path, repo_path)
        repo = Repo(full_path)
        
        full_file_path = _validate_path(full_path, file_path)
        rel_path = os.path.relpath(full_file_path, full_path)
        
        index = repo.open_index()
        path_bytes = rel_path.encode('utf-8')
        
        if path_bytes in index:
            del index[path_bytes]
        
        index.write()
        
        file_deleted = False
        if not cached and os.path.exists(full_file_path):
            os.remove(full_file_path)
            file_deleted = True
        
        return {
            "success": True,
            "data": f"Removed '{file_path}' from staging area",
            "file_deleted": file_deleted
        }
        
    except Exception as e:
        return {"success": False, "error": f"Git rm failed: {str(e)}"}


def _git_mv(sandbox_path: str, params: Dict[str, Any]) -> Dict[str, Any]:
    """
    Move or rename Git tracked files (git mv)
    
    Params:
        - path: Repository path (relative to sandbox, default current dir)
        - src: Source file path
        - dst: Destination file path
    """
    try:
        from dulwich.repo import Repo
        from dulwich.index import Index
    except ImportError:
        return {"success": False, "error": "dulwich not available"}
    
    repo_path = params.get('path', '.')
    src = params.get('src')
    dst = params.get('dst')
    
    if not src or not dst:
        return {"success": False, "error": "Missing src or dst parameter"}
    
    try:
        full_path = _validate_path(sandbox_path, repo_path)
        repo = Repo(full_path)
        
        full_src = _validate_path(full_path, src)
        full_dst = _validate_path(full_path, dst)
        
        rel_src = os.path.relpath(full_src, full_path).encode('utf-8')
        rel_dst = os.path.relpath(full_dst, full_path).encode('utf-8')
        
        index = repo.open_index()
        if rel_src not in index:
            return {"success": False, "error": f"'{src}' is not tracked by git"}
        
        os.makedirs(os.path.dirname(full_dst) or '.', exist_ok=True)
        shutil.move(full_src, full_dst)
        
        index[rel_dst] = index[rel_src]
        del index[rel_src]
        index.write()
        
        return {
            "success": True,
            "data": f"Renamed '{src}' to '{dst}'",
            "stdout": f"📦 Git 移动/重命名\n✅ 已重命名:\n  从: {src}\n  到: {dst}",
            "src": src,
            "dst": dst
        }
        
    except Exception as e:
        return {"success": False, "error": f"Git mv failed: {str(e)}"}


def _exec_script(sandbox_path: str, params: Dict[str, Any]) -> Dict[str, Any]:
    """
    执行 Shell 脚本（支持多行，用系统 sh）
    可以大幅减少 AI 调用次数，将多个命令合并为一次执行
    
    Params:
        - script: 脚本内容字符串（多行，优先级高于 script_path）
        - script_path: 脚本文件路径（相对沙箱，可选）
        - env: 环境变量字典（可选）
        - timeout: 超时秒数（默认 60）
        
    Examples:
        # 批量处理文件
        {"script": "for f in *.txt; do echo 'Processing:' $f; done"}
        
        # 链式文本处理
        {"script": "cat data.csv | grep ERROR | wc -l"}
        
        # 多步骤自动化
        {"script": "mkdir -p output && for f in *.jpg; do convert $f output/${f%.jpg}.png; done"}
    """
    script_content = params.get('script')
    script_path = params.get('script_path')
    env_vars = params.get('env', {})
    timeout = params.get('timeout', 60)
    
    if not script_content and not script_path:
        return {"success": False, "error": "Missing required parameter: script or script_path"}
    
    try:
        # 从文件读取脚本
        if not script_content and script_path:
            full_script_path = _validate_path(sandbox_path, script_path)
            if not os.path.exists(full_script_path):
                return {"success": False, "error": f"Script file not found: {script_path}"}
            with open(full_script_path, 'r', encoding='utf-8') as f:
                script_content = f.read()
        
        # 安全检查 - 禁止危险命令
        dangerous_patterns = [
            'rm -rf /', 'rm -rf /*', '> /dev/sda', 'dd if=/dev/zero',
            'mkfs.', 'reboot', 'shutdown', 'poweroff'
        ]
        for pattern in dangerous_patterns:
            if pattern in script_content.lower():
                return {"success": False, "error": f"Security check failed: forbidden pattern '{pattern}'"}
        
        # 准备环境变量
        env = os.environ.copy() if hasattr(os, 'environ') else {}
        env['HOME'] = sandbox_path
        env['PWD'] = sandbox_path
        env['TMPDIR'] = os.path.join(sandbox_path, '.tmp')
        env.update(env_vars)
        
        # 确保临时目录存在
        os.makedirs(env['TMPDIR'], exist_ok=True)
        
        # 写入临时脚本文件
        temp_script = os.path.join(sandbox_path, '.tmp', 'exec_script.sh')
        with open(temp_script, 'w', encoding='utf-8') as f:
            f.write('#!/system/bin/sh\n')
            f.write('set -e\n')  # 遇到错误立即退出
            f.write(script_content)
        
        # 执行脚本
        result = subprocess.run(
            ['/system/bin/sh', temp_script],
            cwd=sandbox_path,
            capture_output=True,
            text=True,
            timeout=timeout,
            env=env
        )
        
        # 清理临时脚本
        try:
            os.remove(temp_script)
        except:
            pass
        
        return {
            "success": result.returncode == 0,
            "stdout": result.stdout,
            "stderr": result.stderr if result.stderr else None,
            "return_code": result.returncode,
            "lines_executed": len([l for l in script_content.split('\n') if l.strip() and not l.strip().startswith('#')])
        }
        
    except subprocess.TimeoutExpired:
        return {"success": False, "error": f"Script execution timed out after {timeout} seconds"}
    except Exception as e:
        return {"success": False, "error": f"Script execution failed: {str(e)}"}


def _exec_javascript(sandbox_path: str, params: Dict[str, Any]) -> Dict[str, Any]:
    """
    执行 JavaScript 代码
    
    使用 PyMiniRacer (嵌入式 V8) 或 pyjsparser 进行 AST 分析
    主要用于：数据转换、JSON 处理、算法验证
    
    Params:
        - code: JavaScript 代码字符串
        - timeout: 超时秒数（默认 30）
        
    Examples:
        {"code": "JSON.stringify({a: 1, b: 2})"}
        {"code": "[1,2,3].map(x => x * 2).join(',')"}
    """
    code = params.get('code')
    timeout = params.get('timeout', 30)
    
    if not code:
        return {"success": False, "error": "Missing code parameter"}
    
    try:
        # 尝试使用 PyMiniRacer (如果已安装)
        try:
            from py_mini_racer import MiniRacer
            ctx = MiniRacer()
            result = ctx.eval(code)
            return {
                "success": True,
                "result": result,
                "language": "javascript"
            }
        except ImportError:
            pass
        
        # 备选：使用 pyjsparser 进行 AST 分析 + 简单表达式求值
        import json
        import re
        
        # 简单的表达式求值器 (支持常见场景)
        # 替换 JavaScript 语法为 Python
        py_code = code
        py_code = re.sub(r'const\s+', '', py_code)
        py_code = re.sub(r'let\s+', '', py_code)
        py_code = re.sub(r'var\s+', '', py_code)
        py_code = re.sub(r'console\.log\s*\(', 'print(', py_code)
        py_code = re.sub(r'JSON\.stringify\s*\(', 'json.dumps(', py_code)
        py_code = re.sub(r'JSON\.parse\s*\(', 'json.loads(', py_code)
        py_code = re.sub(r'null', 'None', py_code)
        py_code = re.sub(r'true', 'True', py_code)
        py_code = re.sub(r'false', 'False', py_code)
        
        # 执行转换后的代码
        exec_globals = {'json': json, 'print': print}
        exec_locals = {}
        
        import io
        import sys
        old_stdout = sys.stdout
        stdout_capture = io.StringIO()
        sys.stdout = stdout_capture
        
        try:
            exec(compile(py_code, '<javascript>', 'exec'), exec_globals, exec_locals)
        finally:
            sys.stdout = old_stdout
        
        output = stdout_capture.getvalue()
        
        return {
            "success": True,
            "stdout": output,
            "transpiled_python": py_code,
            "note": "JavaScript executed via Python transpilation (PyMiniRacer not installed)"
        }
        
    except Exception as e:
        return {"success": False, "error": f"JavaScript execution failed: {str(e)}"}


def _exec_lua(sandbox_path: str, params: Dict[str, Any]) -> Dict[str, Any]:
    """
    执行/分析 Lua 代码
    
    由于 Chaquopy 上 Lua 解释器不可用，目前提供：
    1. 语法检查和验证
    2. 转换为 Python 执行（简化版）
    3. 代码结构分析
    
    适合：配置文件处理、代码分析、学习 Lua
    
    Params:
        - code: Lua 代码字符串
        - script_path: 或从文件读取
        - transpile: 是否转换为 Python 执行（默认 False）
        
    Examples:
        {"code": "return 1 + 2"}
        {"code": "for i=1,10 do print(i) end", "transpile": true}
    """
    code = params.get('code')
    script_path = params.get('script_path')
    transpile = params.get('transpile', False)
    
    if not code and not script_path:
        return {"success": False, "error": "Missing code or script_path parameter"}
    
    if script_path:
        full_path = _validate_path(sandbox_path, script_path)
        if not os.path.exists(full_path):
            return {"success": False, "error": f"Script file not found: {script_path}"}
        with open(full_path, 'r', encoding='utf-8') as f:
            code = f.read()
    
    try:
        # Lua 语法分析
        issues = []
        warnings = []
        
        # 1. 检查括号匹配
        function_count = len([m for m in __import__('re').finditer(r'\bfunction\b', code)])
        end_count = code.count('end')
        if function_count > 0 and abs(function_count - end_count) > 2:
            issues.append(f"Possible 'end' mismatch (functions: {function_count}, ends: {end_count})")
        
        # 2. 检查字符串匹配
        single_quotes = code.count("'") - code.count("\\'")
        double_quotes = code.count('"') - code.count('\\"')
        if single_quotes % 2 != 0:
            warnings.append("Unmatched single quotes")
        if double_quotes % 2 != 0:
            warnings.append("Unmatched double quotes")
        
        # 3. 检查括号匹配
        open_parens = code.count('(')
        close_parens = code.count(')')
        if open_parens != close_parens:
            issues.append(f"Parenthesis mismatch: {open_parens} open, {close_parens} close")
        
        # 4. 检测代码结构
        has_function = 'function' in code
        has_loop = 'for ' in code or 'while ' in code
        has_conditional = 'if ' in code
        has_table = '{' in code and '}' in code
        
        result = {
            "success": True,
            "language": "lua",
            "syntax_valid": len(issues) == 0,
            "issues": issues,
            "warnings": warnings,
            "structure": {
                "has_function": has_function,
                "has_loop": has_loop,
                "has_conditional": has_conditional,
                "has_table": has_table,
                "lines": code.count('\n') + 1
            },
            "note": "Lua syntax analysis (execution requires native Lua interpreter)"
        }
        
        # 如果需要，转换为 Python 执行
        if transpile:
            import re
            
            # 简单的 Lua -> Python 转换
            py_code = code
            
            # 转换函数定义
            py_code = re.sub(r'function\s+(\w+)\s*\((.*?)\)', r'def \1(\2):', py_code)
            py_code = re.sub(r'local\s+function\s+(\w+)\s*\((.*?)\)', r'def \1(\2):', py_code)
            
            # 转换 local 变量
            py_code = re.sub(r'\blocal\s+', '', py_code)
            
            # 转换 print
            py_code = re.sub(r'\bprint\s*\(', 'print(', py_code)
            
            # 转换字符串连接
            py_code = re.sub(r'\.\.', ' + ', py_code)
            
            # 转换 # 操作符（长度）
            py_code = re.sub(r'#(\w+)', r'len(\1)', py_code)
            
            # 转换 table 访问
            py_code = re.sub(r'(\w+)\[(\w+)\]', r'\1[\2]', py_code)
            
            # 转换 ipairs/pairs 循环（简化）
            py_code = re.sub(r'for\s+(\w+)\s*,\s*(\w+)\s+in\s+ipairs\((\w+)\)\s+do', 
                           r'for \1, \2 in enumerate(\3):', py_code)
            py_code = re.sub(r'for\s+(\w+)\s*,\s*(\w+)\s+in\s+pairs\((\w+)\)\s+do', 
                           r'for \1, \2 in \3.items():', py_code)
            
            # 转换简单 for 循环
            py_code = re.sub(r'for\s+(\w+)\s*=\s*(\d+),\s*(\d+)\s+do', 
                           r'for \1 in range(\2, \3 + 1):', py_code)
            
            # 转换 while
            py_code = re.sub(r'while\s+(.+?)\s+do', r'while \1:', py_code)
            
            # 转换 if
            py_code = re.sub(r'if\s+(.+?)\s+then', r'if \1:', py_code)
            py_code = re.sub(r'elseif\s+(.+?)\s+then', r'elif \1:', py_code)
            py_code = re.sub(r'\belse\b', 'else:', py_code)
            
            # 移除 end 关键字
            py_code = re.sub(r'\bend\b', '', py_code)
            
            # 转换 nil
            py_code = re.sub(r'\bnil\b', 'None', py_code)
            
            # 转换 and/or/not
            py_code = re.sub(r'\band\b', 'and', py_code)
            py_code = re.sub(r'\bor\b', 'or', py_code)
            py_code = re.sub(r'\bnot\b', 'not', py_code)
            
            # 尝试执行转换后的代码
            try:
                exec_globals = {
                    'len': len,
                    'print': print,
                    'range': range,
                    'enumerate': enumerate
                }
                exec_locals = {}
                
                import io
                import sys
                old_stdout = sys.stdout
                stdout_capture = io.StringIO()
                sys.stdout = stdout_capture
                
                exec(compile(py_code, '<lua_transpiled>', 'exec'), exec_globals, exec_locals)
                
                sys.stdout = old_stdout
                output = stdout_capture.getvalue()
                
                result['transpiled_python'] = py_code
                result['execution_output'] = output
                result['note'] = 'Lua transpiled to Python and executed'
                
            except Exception as e:
                result['transpiled_python'] = py_code
                result['transpile_error'] = str(e)
                result['note'] = 'Lua transpiled but execution failed'
        
        return result
        
    except Exception as e:
        return {"success": False, "error": f"Lua analysis failed: {str(e)}"}


def _analyze_code(sandbox_path: str, params: Dict[str, Any]) -> Dict[str, Any]:
    """
    代码分析与质量检查（支持多语言）
    
    用于 AI 自举开发：语法检查、复杂度分析、生成补丁
    
    Params:
        - file_path: 要分析的文件路径
        - language: 语言类型 (python/kotlin/javascript/lua)
        - operation: 分析类型 (syntax/complexity/ast/patch)
        
    Returns:
        - syntax_valid: 语法是否正确
        - issues: 问题列表
        - metrics: 代码度量（行数、复杂度等）
        - ast_dump: AST 结构（可选）
    """
    file_path = params.get('file_path')
    language = params.get('language', 'auto')
    operation = params.get('operation', 'syntax')
    
    if not file_path:
        return {"success": False, "error": "Missing file_path parameter"}
    
    try:
        full_path = _validate_path(sandbox_path, file_path)
        if not os.path.exists(full_path):
            return {"success": False, "error": f"File not found: {file_path}"}
        
        with open(full_path, 'r', encoding='utf-8') as f:
            code = f.read()
        
        # 自动检测语言
        if language == 'auto':
            ext = os.path.splitext(file_path)[1].lower()
            lang_map = {
                '.py': 'python',
                '.kt': 'kotlin',
                '.js': 'javascript',
                '.lua': 'lua',
                '.sh': 'shell',
                '.json': 'json',
                '.yaml': 'yaml',
                '.yml': 'yaml'
            }
            language = lang_map.get(ext, 'unknown')
        
        result = {
            "success": True,
            "file_path": file_path,
            "language": language,
            "file_size": len(code),
            "lines": code.count('\n') + 1
        }
        
        if language == 'python':
            # Python AST 分析
            import ast
            try:
                tree = ast.parse(code)
                result['syntax_valid'] = True
                
                if operation == 'complexity':
                    # 简单的复杂度分析
                    func_count = len([n for n in ast.walk(tree) if isinstance(n, ast.FunctionDef)])
                    class_count = len([n for n in ast.walk(tree) if isinstance(n, ast.ClassDef)])
                    import_count = len([n for n in ast.walk(tree) if isinstance(n, ast.Import)])
                    
                    result['metrics'] = {
                        'functions': func_count,
                        'classes': class_count,
                        'imports': import_count,
                        'complexity_score': func_count * 2 + class_count * 3
                    }
                
                if operation == 'ast':
                    # 返回 AST 结构（简化版）
                    result['ast_dump'] = ast.dump(tree, indent=2)[:5000]  # 限制大小
                    
            except SyntaxError as e:
                result['syntax_valid'] = False
                result['syntax_error'] = f"Line {e.lineno}: {e.msg}"
                
        elif language == 'kotlin':
            # Kotlin 基础分析（文本层面）
            # 完整的分析需要 kotlinc，这里做基础检查
            issues = []
            
            # 检查基本语法结构
            open_braces = code.count('{')
            close_braces = code.count('}')
            open_parens = code.count('(')
            close_parens = code.count(')')
            
            if open_braces != close_braces:
                issues.append(f"Brace mismatch: {open_braces} open, {close_braces} close")
            if open_parens != close_parens:
                issues.append(f"Parenthesis mismatch: {open_parens} open, {close_parens} close")
            
            # 检查常见关键字
            keywords = ['fun ', 'class ', 'val ', 'var ', 'import ', 'package ']
            found_keywords = [kw for kw in keywords if kw in code]
            
            result['syntax_valid'] = len(issues) == 0
            result['issues'] = issues
            result['detected_keywords'] = found_keywords
            result['note'] = 'Basic syntax check only (full validation requires kotlinc)'
            
        elif language == 'javascript':
            # JavaScript 基础分析
            issues = []
            
            # 检查括号匹配
            open_braces = code.count('{')
            close_braces = code.count('}')
            open_parens = code.count('(')
            close_parens = code.count(')')
            open_brackets = code.count('[')
            close_brackets = code.count(']')
            
            if open_braces != close_braces:
                issues.append(f"Brace mismatch: {open_braces} open, {close_braces} close")
            if open_parens != close_parens:
                issues.append(f"Parenthesis mismatch: {open_parens} open, {close_parens} close")
            if open_brackets != close_brackets:
                issues.append(f"Bracket mismatch: {open_brackets} open, {close_brackets} close")
            
            # 检查字符串引号匹配（简单检查）
            single_quotes = code.count("'") - code.count("\\'")
            double_quotes = code.count('"') - code.count('\\"')
            
            # 检查常见语法错误
            if 'function function' in code:
                issues.append("Duplicate 'function' keyword")
            if 'const const' in code or 'let let' in code or 'var var' in code:
                issues.append("Duplicate declaration keyword")
            
            # 检测代码结构
            has_function = 'function' in code or '=>' in code
            has_class = 'class ' in code
            has_async = 'async ' in code
            has_await = 'await ' in code
            
            result['syntax_valid'] = len(issues) == 0
            result['issues'] = issues
            result['structure'] = {
                'has_function': has_function,
                'has_class': has_class,
                'has_async': has_async,
                'has_await': has_await
            }
            result['note'] = 'Basic syntax check only (full validation requires Node.js)'
                
        elif language == 'lua':
            # Lua 基础检查
            issues = []
            
            # 检查 end 关键字匹配
            function_count = code.count('function')
            end_count = code.count('end')
            
            # 粗略估计（不准确，但作为启发式检查）
            if abs(function_count - end_count) > 2:
                issues.append(f"Possible 'end' mismatch (functions: {function_count}, ends: {end_count})")
            
            result['syntax_valid'] = len(issues) == 0
            result['issues'] = issues
            result['note'] = 'Basic heuristic check only'
        
        return result
        
    except Exception as e:
        return {"success": False, "error": f"Code analysis failed: {str(e)}"}


def _compile_check(sandbox_path: str, params: Dict[str, Any]) -> Dict[str, Any]:
    """
    轻量级编译/语法验证（内置在沙箱中）
    
    支持多种语言的语法检查、格式化、风格验证
    无需外部编译器，使用 Python 生态工具
    
    Params:
        - file_path: 要检查的文件路径
        - language: 语言类型 (python/kotlin/java/shell/markdown/json/yaml)
        - check_type: 检查类型 (syntax/format/lint/all，默认 all)
        - fix: 是否自动修复问题（默认 false）
        
    Returns:
        - valid: 是否通过验证
        - issues: 问题列表（行号、级别、消息）
        - fixed_content: 修复后的内容（如果 fix=true）
        - tool_used: 使用的工具
        
    Examples:
        {"file_path": "script.py", "language": "python", "check_type": "all"}
        {"file_path": "code.kt", "language": "kotlin", "fix": true}
    """
    file_path = params.get('file_path')
    language = params.get('language', 'auto')
    check_type = params.get('check_type', 'all')
    fix = params.get('fix', False)
    
    if not file_path:
        return {"success": False, "error": "Missing file_path parameter"}
    
    try:
        full_path = _validate_path(sandbox_path, file_path)
        if not os.path.exists(full_path):
            return {"success": False, "error": f"File not found: {file_path}"}
        
        with open(full_path, 'r', encoding='utf-8', errors='replace') as f:
            content = f.read()
        
        # 自动检测语言
        if language == 'auto':
            ext = os.path.splitext(file_path)[1].lower()
            lang_map = {
                '.py': 'python',
                '.kt': 'kotlin',
                '.java': 'java',
                '.sh': 'shell',
                '.md': 'markdown',
                '.json': 'json',
                '.yaml': 'yaml',
                '.yml': 'yaml',
                '.toml': 'toml',
                '.lua': 'lua',
                '.js': 'javascript',
                '.css': 'css',
                '.html': 'html',
            }
            language = lang_map.get(ext, 'unknown')
        
        result = {
            "success": True,
            "file_path": file_path,
            "language": language,
            "check_type": check_type,
            "valid": True,
            "issues": [],
            "tool_used": None
        }
        
        # ========== Python ==========
        if language == 'python':
            issues = []
            
            # 1. 语法检查（AST）
            if check_type in ('syntax', 'all'):
                try:
                    import ast
                    ast.parse(content)
                except SyntaxError as e:
                    issues.append({
                        "line": e.lineno or 1,
                        "column": e.offset or 0,
                        "level": "error",
                        "message": f"SyntaxError: {e.msg}",
                        "tool": "ast"
                    })
                    result['valid'] = False
            
            # 2. 使用 ruff（如果安装了）
            if check_type in ('lint', 'all'):
                try:
                    import subprocess
                    import json as json_mod
                    
                    ruff_result = subprocess.run(
                        ['python', '-m', 'ruff', 'check', full_path, '--output-format=json'],
                        capture_output=True,
                        text=True,
                        timeout=30
                    )
                    
                    if ruff_result.stdout:
                        try:
                            ruff_issues = json_mod.loads(ruff_result.stdout)
                            for issue in ruff_issues:
                                issues.append({
                                    "line": issue.get('location', {}).get('row', 1),
                                    "column": issue.get('location', {}).get('column', 0),
                                    "level": issue.get('code', 'WARNING'),
                                    "message": issue.get('message', 'Unknown issue'),
                                    "tool": "ruff"
                                })
                                if issue.get('code', '').startswith('E'):
                                    result['valid'] = False
                        except:
                            pass
                            
                    result['tool_used'] = 'ruff'
                except (ImportError, subprocess.TimeoutExpired, FileNotFoundError):
                    # ruff 不可用，回退到 pylint
                    try:
                        import subprocess
                        pylint_result = subprocess.run(
                            ['python', '-m', 'pylint', full_path, '--output-format=json'],
                            capture_output=True,
                            text=True,
                            timeout=30
                        )
                        
                        if pylint_result.stdout:
                            try:
                                import json as json_mod
                                pylint_issues = json_mod.loads(pylint_result.stdout)
                                for issue in pylint_issues:
                                    if issue.get('type') == 'error':
                                        issues.append({
                                            "line": issue.get('line', 1),
                                            "column": issue.get('column', 0),
                                            "level": "error",
                                            "message": issue.get('message', 'Unknown'),
                                            "tool": "pylint"
                                        })
                                        result['valid'] = False
                            except:
                                pass
                        result['tool_used'] = 'pylint'
                    except:
                        pass
            
            # 3. 格式化检查/修复
            if check_type in ('format', 'all') or fix:
                try:
                    import subprocess
                    
                    if fix:
                        # 使用 black 格式化
                        black_result = subprocess.run(
                            ['python', '-m', 'black', full_path, '--quiet'],
                            capture_output=True,
                            text=True,
                            timeout=30
                        )
                        if black_result.returncode == 0:
                            with open(full_path, 'r', encoding='utf-8') as f:
                                result['fixed_content'] = f.read()
                            result['formatted'] = True
                    else:
                        # 检查是否需要格式化
                        black_check = subprocess.run(
                            ['python', '-m', 'black', '--check', full_path],
                            capture_output=True,
                            text=True,
                            timeout=30
                        )
                        if black_check.returncode != 0:
                            issues.append({
                                "line": 1,
                                "column": 0,
                                "level": "style",
                                "message": "Code needs formatting (run with fix=true)",
                                "tool": "black"
                            })
                except:
                    pass
        
        # ========== JSON ==========
        elif language == 'json':
            import json as json_mod
            try:
                json_mod.loads(content)
                result['valid'] = True
                result['tool_used'] = 'json'
            except json_mod.JSONDecodeError as e:
                issues.append({
                    "line": e.lineno or 1,
                    "column": e.colno or 0,
                    "level": "error",
                    "message": f"JSON Error: {e.msg}",
                    "tool": "json"
                })
                result['valid'] = False
        
        # ========== YAML ==========
        elif language == 'yaml':
            try:
                import yaml
                yaml.safe_load(content)
                result['valid'] = True
                result['tool_used'] = 'yaml'
            except yaml.YAMLError as e:
                if hasattr(e, 'problem_mark'):
                    mark = e.problem_mark
                    issues.append({
                        "line": mark.line + 1 if mark else 1,
                        "column": mark.column + 1 if mark else 0,
                        "level": "error",
                        "message": f"YAML Error: {e.problem}",
                        "tool": "yaml"
                    })
                else:
                    issues.append({
                        "line": 1,
                        "column": 0,
                        "level": "error",
                        "message": f"YAML Error: {str(e)}",
                        "tool": "yaml"
                    })
                result['valid'] = False
        
        # ========== TOML ==========
        elif language == 'toml':
            try:
                import tomllib  # Python 3.11+
                tomllib.loads(content)
                result['valid'] = True
                result['tool_used'] = 'tomllib'
            except ImportError:
                try:
                    import toml
                    toml.loads(content)
                    result['valid'] = True
                    result['tool_used'] = 'toml'
                except Exception as e:
                    issues.append({
                        "line": 1,
                        "column": 0,
                        "level": "error",
                        "message": f"TOML Error: {str(e)}",
                        "tool": "toml"
                    })
                    result['valid'] = False
            except Exception as e:
                issues.append({
                    "line": 1,
                    "column": 0,
                    "level": "error",
                    "message": f"TOML Error: {str(e)}",
                    "tool": "tomllib"
                })
                result['valid'] = False
        
        # ========== Shell ==========
        elif language == 'shell':
            # 基础语法检查
            issues = []
            
            # 检查常见错误
            lines = content.split('\n')
            for i, line in enumerate(lines, 1):
                stripped = line.strip()
                
                # 检查未闭合的引号
                single_quotes = stripped.count("'") - stripped.count("\\'")
                double_quotes = stripped.count('"') - stripped.count('\\"')
                
                # 简单启发式检查
                if 'if [' in stripped and '];' not in stripped and ' then' not in stripped:
                    issues.append({
                        "line": i,
                        "column": 0,
                        "level": "warning",
                        "message": "Possible missing 'then' or semicolon in if statement",
                        "tool": "shellcheck-lite"
                    })
                
                if stripped.startswith('function ') and '()' not in stripped:
                    # 检查 function 定义格式
                    pass
            
            # 尝试用 sh -n 检查语法
            try:
                import subprocess
                check_result = subprocess.run(
                    ['/system/bin/sh', '-n', full_path],
                    capture_output=True,
                    text=True,
                    timeout=10
                )
                if check_result.returncode != 0:
                    issues.append({
                        "line": 1,
                        "column": 0,
                        "level": "error",
                        "message": f"Shell syntax error: {check_result.stderr}",
                        "tool": "sh"
                    })
                    result['valid'] = False
                else:
                    result['valid'] = len([i for i in issues if i['level'] == 'error']) == 0
            except:
                result['valid'] = len(issues) == 0
            
            result['tool_used'] = 'shellcheck-lite'
            result['issues'] = issues
        
        # ========== Kotlin/Java ==========
        elif language in ('kotlin', 'java'):
            issues = []
            ktlint_available = False
            
            # 尝试使用 ktlint（Kotlin 首选）
            if language == 'kotlin':
                ktlint_path = _get_ktlint_path(sandbox_path)
                
                if ktlint_path and os.path.exists(ktlint_path):
                    ktlint_available = True
                    
                    try:
                        # 准备 ktlint 参数
                        ktlint_args = [ktlint_path, full_path, '--reporter=json']
                        
                        if fix:
                            ktlint_args.append('--format')
                        
                        # 运行 ktlint
                        ktlint_result = subprocess.run(
                            ktlint_args,
                            capture_output=True,
                            text=True,
                            timeout=60
                        )
                        
                        # 解析 ktlint JSON 输出
                        if ktlint_result.stdout:
                            try:
                                ktlint_issues = json_mod.loads(ktlint_result.stdout)
                                for issue in ktlint_issues:
                                    # 确定错误级别
                                    rule_id = issue.get('ruleId', '')
                                    if rule_id in ['indent', 'no-wildcard-imports', 'colon-spacing']:
                                        level = 'error' if not fix else 'style'
                                    else:
                                        level = 'warning'
                                    
                                    issues.append({
                                        "line": issue.get('line', 1),
                                        "column": issue.get('column', 0),
                                        "level": level,
                                        "message": issue.get('detail', issue.get('message', 'Unknown issue')),
                                        "rule": rule_id,
                                        "tool": "ktlint"
                                    })
                                    
                                    if level == 'error':
                                        result['valid'] = False
                            except json_mod.JSONDecodeError:
                                # 可能是格式化的输出，不是 JSON
                                pass
                        
                        # 如果 fix=true，读取修复后的文件
                        if fix and ktlint_result.returncode == 0:
                            with open(full_path, 'r', encoding='utf-8') as f:
                                result['fixed_content'] = f.read()
                            result['formatted'] = True
                        
                        result['tool_used'] = 'ktlint'
                        result['ktlint_version'] = os.path.basename(ktlint_path).replace('ktlint-', '')
                        
                    except Exception as e:
                        # ktlint 运行失败，回退到基础检查
                        issues.append({
                            "line": 1,
                            "column": 0,
                            "level": "warning",
                            "message": f"ktlint failed: {str(e)}, falling back to basic check",
                            "tool": "system"
                        })
                        ktlint_available = False
            
            # 如果没有 ktlint 或 ktlint 失败，进行基础检查
            if not ktlint_available:
                # 括号匹配
                open_braces = content.count('{')
                close_braces = content.count('}')
                open_parens = content.count('(')
                close_parens = content.count(')')
                
                if open_braces != close_braces:
                    issues.append({
                        "line": 1,
                        "column": 0,
                        "level": "error",
                        "message": f"Brace mismatch: {open_braces} open, {close_braces} close",
                        "tool": "syntax"
                    })
                    result['valid'] = False
                
                if open_parens != close_parens:
                    issues.append({
                        "line": 1,
                        "column": 0,
                        "level": "error",
                        "message": f"Parenthesis mismatch: {open_parens} open, {close_parens} close",
                        "tool": "syntax"
                    })
                    result['valid'] = False
                
                # 检查常见 Kotlin 问题
                # 1. 检查函数命名（应该是 camelCase）
                import re
                func_pattern = r'fun\s+([A-Z][a-zA-Z0-9]*)\s*\('
                bad_funcs = re.findall(func_pattern, content)
                for func in bad_funcs:
                    issues.append({
                        "line": 1,
                        "column": 0,
                        "level": "warning",
                        "message": f"Function '{func}' should start with lowercase (camelCase)",
                        "tool": "style-check"
                    })
                
                # 2. 检查类命名（应该是 PascalCase）
                class_pattern = r'class\s+([a-z][a-zA-Z0-9]*)'
                bad_classes = re.findall(class_pattern, content)
                for cls in bad_classes:
                    issues.append({
                        "line": 1,
                        "column": 0,
                        "level": "warning",
                        "message": f"Class '{cls}' should start with uppercase (PascalCase)",
                        "tool": "style-check"
                    })
                
                # 3. 检测可能的未使用导入（简单启发式）
                import_pattern = r'import\s+([\w.]+)'
                imports = re.findall(import_pattern, content)
                for imp in imports:
                    # 获取最后一部分（类名或函数名）
                    name = imp.split('.')[-1]
                    # 粗略检查是否被使用
                    if name not in ['*'] and content.count(name) <= 1:  # 只在 import 中出现一次
                        issues.append({
                            "line": 1,
                            "column": 0,
                            "level": "info",
                            "message": f"Import '{imp}' may be unused ( heuristic check)",
                            "tool": "unused-check"
                        })
                
                result['tool_used'] = 'syntax-check'
                result['note'] = 'Basic syntax check only. For deeper analysis, run: {"operation": "install_tool", "params": {"tool": "ktlint"}}'
            
            result['issues'] = issues
            if result['valid'] is None:
                result['valid'] = len([i for i in issues if i['level'] == 'error']) == 0
        
        # ========== Markdown ==========
        elif language == 'markdown':
            # Markdown 基础检查
            issues = []
            
            # 检查链接格式
            import re
            links = re.findall(r'\[([^\]]+)\]\(([^)]+)\)', content)
            for text, url in links:
                if not url.startswith(('http://', 'https://', '#', './', '../', '/')):
                    issues.append({
                        "line": 1,
                        "column": 0,
                        "level": "warning",
                        "message": f"Suspicious link format: {url}",
                        "tool": "markdown-lint"
                    })
            
            # 检查标题层次
            headers = re.findall(r'^(#{1,6})', content, re.MULTILINE)
            prev_level = 0
            for header in headers:
                level = len(header)
                if level > prev_level + 1 and prev_level > 0:
                    issues.append({
                        "line": 1,
                        "column": 0,
                        "level": "warning",
                        "message": f"Header level jumps from {prev_level} to {level}",
                        "tool": "markdown-lint"
                    })
                prev_level = level
            
            result['valid'] = len([i for i in issues if i['level'] == 'error']) == 0
            result['issues'] = issues
            result['tool_used'] = 'markdown-lint'
        
        # ========== 其他语言 ==========
        else:
            result['valid'] = 'unknown'
            result['note'] = f"Language '{language}' not fully supported for compile_check"
        
        result['issues_count'] = len(result.get('issues', []))
        result['error_count'] = len([i for i in result.get('issues', []) if i.get('level') == 'error'])
        result['warning_count'] = len([i for i in result.get('issues', []) if i.get('level') == 'warning'])
        
        return result
        
    except Exception as e:
        import traceback
        return {
            "success": False,
            "error": f"Compile check failed: {str(e)}",
            "traceback": traceback.format_exc()
        }


# ========== 工具安装管理 ==========

# 工具下载配置
TOOL_DOWNLOAD_URLS = {
    'ktlint': {
        'url': 'https://github.com/pinterest/ktlint/releases/download/{version}/ktlint',
        'default_version': '1.2.1',
        'executable': True,
    },
    'ktlint_android': {
        'url': 'https://github.com/pinterest/ktlint/releases/download/{version}/ktlint',
        'default_version': '1.2.1',
        'executable': True,
        'is_android_variant': True,
    }
}


def _install_tool(sandbox_path: str, params: Dict[str, Any]) -> Dict[str, Any]:
    """
    安装开发工具到沙箱（ktlint 等）
    
    支持的工具：
    - ktlint: Kotlin 代码检查工具 (~10MB)
    
    Params:
        - tool: 工具名称 (ktlint)
        - version: 版本号（可选，默认最新稳定版）
        - force: 强制重新安装（默认 false）
        
    Returns:
        - installed: 是否安装成功
        - path: 工具路径
        - version: 实际版本
        - size_mb: 工具大小
        
    Examples:
        {"tool": "ktlint"}
        {"tool": "ktlint", "version": "1.2.1"}
        {"tool": "ktlint", "force": true}
    """
    tool_name = params.get('tool')
    version = params.get('version')
    force = params.get('force', False)
    
    if not tool_name:
        return {"success": False, "error": "Missing tool parameter"}
    
    tool_name = tool_name.lower()
    
    if tool_name not in TOOL_DOWNLOAD_URLS:
        available = list(TOOL_DOWNLOAD_URLS.keys())
        return {"success": False, "error": f"Unknown tool: {tool_name}. Available: {available}"}
    
    config = TOOL_DOWNLOAD_URLS[tool_name]
    version = version or config['default_version']
    
    # 工具目录
    tools_dir = os.path.join(sandbox_path, '.tools')
    tool_path = os.path.join(tools_dir, f"{tool_name}-{version}")
    
    try:
        # 检查是否已安装
        if os.path.exists(tool_path) and not force:
            file_size = os.path.getsize(tool_path) / (1024 * 1024)
            return {
                "success": True,
                "data": f"Tool {tool_name} v{version} already installed",
                "installed": True,
                "path": tool_path,
                "version": version,
                "size_mb": round(file_size, 2),
                "cached": True
            }
        
        # 创建工具目录
        os.makedirs(tools_dir, exist_ok=True)
        
        # 下载工具
        download_url = config['url'].format(version=version)
        
        # 显示进度
        print(f"Downloading {tool_name} v{version}...")
        print(f"URL: {download_url}")
        
        # 使用 requests 下载
        try:
            import requests
            response = requests.get(download_url, timeout=120, stream=True)
            response.raise_for_status()
            
            total_size = int(response.headers.get('content-length', 0))
            downloaded = 0
            
            with open(tool_path, 'wb') as f:
                for chunk in response.iter_content(chunk_size=8192):
                    if chunk:
                        f.write(chunk)
                        downloaded += len(chunk)
                        if total_size > 0:
                            percent = (downloaded / total_size) * 100
                            if downloaded % (1024 * 1024) < 8192:  # 每 MB 打印一次
                                print(f"Progress: {percent:.1f}% ({downloaded/(1024*1024):.1f} MB)")
            
            # 设置可执行权限
            if config.get('executable'):
                os.chmod(tool_path, 0o755)
            
            file_size = os.path.getsize(tool_path) / (1024 * 1024)
            
            # 验证安装（简单测试）
            if tool_name == 'ktlint':
                test_result = subprocess.run(
                    [tool_path, '--version'],
                    capture_output=True,
                    text=True,
                    timeout=10
                )
                actual_version = test_result.stdout.strip() if test_result.returncode == 0 else version
            else:
                actual_version = version
            
            return {
                "success": True,
                "data": f"Tool {tool_name} v{actual_version} installed successfully",
                "installed": True,
                "path": tool_path,
                "version": actual_version,
                "size_mb": round(file_size, 2),
                "cached": False
            }
            
        except requests.RequestException as e:
            # 清理失败的下载
            if os.path.exists(tool_path):
                os.remove(tool_path)
            return {"success": False, "error": f"Download failed: {str(e)}"}
            
    except Exception as e:
        # 清理失败的安装
        if os.path.exists(tool_path):
            try:
                os.remove(tool_path)
            except:
                pass
        return {"success": False, "error": f"Installation failed: {str(e)}"}


def _get_ktlint_path(sandbox_path: str, version: str = None) -> Optional[str]:
    """获取 ktlint 路径，如果未安装返回 None"""
    tools_dir = os.path.join(sandbox_path, '.tools')
    
    if version:
        tool_path = os.path.join(tools_dir, f"ktlint-{version}")
        if os.path.exists(tool_path):
            return tool_path
    else:
        # 查找任意版本
        import glob
        pattern = os.path.join(tools_dir, 'ktlint-*')
        matches = glob.glob(pattern)
        if matches:
            # 返回最新版本（按文件名排序）
            return sorted(matches)[-1]
    
    return None


# 兼容旧接口
run = execute
