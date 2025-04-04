# coding=utf-8

#  Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

import pytest
import sys
from _pytest.config import get_plugin_manager
import warnings

if sys.version_info[:2] >= (3, 10):
    from importlib.metadata import entry_points as iter_entry_points
else:
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", category=DeprecationWarning)
        from pkg_resources import iter_entry_points

from _jb_runner_tools import jb_patch_separator, jb_doc_args, JB_DISABLE_BUFFERING, \
    start_protocol, parse_arguments, set_parallel_mode, jb_finish_tests, \
    jb_patch_targets
from teamcity import pytest_plugin
import os

_DOCTEST_MODULES_ARG = "--doctest-modules"

def _add_module_to_target(module_name, python_parts):
    # Doctest: Find the fully qualified name of the target module by checking each
    # directory level if they have an __init__.py file
    fully_qualified_name = []
    path = os.path.abspath(module_name.replace("/", os.sep))
    while True:
        fully_qualified_name.insert(0, os.path.basename(path))
        path = os.path.dirname(path)
        init_py_path = os.path.join(path, "__init__.py")
        if not os.path.exists(init_py_path):
            break

    return  ".".join(fully_qualified_name) + "." + python_parts


if __name__ == '__main__':
    path, targets, additional_args = parse_arguments()
    sys.argv += additional_args

    # Path pytest targets:
    if _DOCTEST_MODULES_ARG in additional_args:
        # Doctest: path_to_file.py::module_name.class_name.fun_name
        joined_targets = jb_patch_targets(targets, '/', '::', '.', '.py::', _add_module_to_target)
    else:
        # Pytest: path_to_file.py::module_name::class_name::fun_name
        joined_targets = jb_patch_separator(targets, fs_glue="/", python_glue="::", fs_to_python_glue=".py::")

    # When file is launched in pytest it should be file.py: you can't provide it as bare module
    joined_targets = [t + ".py" if ":" not in t else t for t in joined_targets]
    sys.argv += [path] if path else joined_targets

    # plugin is discovered automatically in 3, but not in 2
    # to prevent "plugin already registered" problem we check it first
    plugins_to_load = []
    if not get_plugin_manager().hasplugin("pytest-teamcity"):
        if "pytest-teamcity" not in map(lambda e: e.name, iter_entry_points(group='pytest11')):
            plugins_to_load.append(pytest_plugin)

    args = sys.argv[1:]
    if "--jb-show-summary" in args:
        args.remove("--jb-show-summary")
    elif int(pytest.__version__.split('.')[0]) >= 6:
        args += ["--no-header", "--no-summary", "-q"]

    if JB_DISABLE_BUFFERING and "-s" not in args:
      args += ["-s"]


    jb_doc_args("pytest", args)


    class Plugin:
        @staticmethod
        def pytest_configure(config):
            if getattr(config.option, "numprocesses", None):
                set_parallel_mode()
            start_protocol()

    os.environ["_JB_PPRINT_PRIMITIVES"] = "1"
    try:
        sys.exit(pytest.main(args, plugins_to_load + [Plugin]))
    finally:
        jb_finish_tests()
