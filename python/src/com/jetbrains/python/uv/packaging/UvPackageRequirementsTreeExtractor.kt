// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.uv.packaging

import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.python.pyproject.model.api.isPyProjectTomlBased
import com.jetbrains.python.packaging.PyPackageName
import com.jetbrains.python.packaging.common.PythonPackage
import com.jetbrains.python.packaging.packageRequirements.PackageCollectionPackageStructureNode
import com.jetbrains.python.packaging.packageRequirements.PackageNode
import com.jetbrains.python.packaging.packageRequirements.PackageStructureNode
import com.jetbrains.python.packaging.packageRequirements.PythonPackageRequirementsTreeExtractor
import com.jetbrains.python.packaging.packageRequirements.PythonPackageRequirementsTreeExtractor.Companion.parseTree
import com.jetbrains.python.packaging.packageRequirements.TreeParser
import com.jetbrains.python.packaging.packageRequirements.PythonPackageRequirementsTreeExtractorProvider
import com.jetbrains.python.packaging.packageRequirements.WorkspaceMemberPackageStructureNode
import com.jetbrains.python.getOrNull
import com.jetbrains.python.sdk.uv.UvLowLevel
import com.jetbrains.python.sdk.uv.UvSdkAdditionalData
import com.jetbrains.python.sdk.uv.impl.createUvLowLevel
import com.jetbrains.python.sdk.uv.isUv
import java.nio.file.Path

internal class UvPackageRequirementsTreeExtractor(private val uvWorkingDirectory: Path?, private val project: Project) : PythonPackageRequirementsTreeExtractor {

  override suspend fun extract(declaredPackageNames: Set<String>): PackageStructureNode {
    val uv = uvWorkingDirectory?.let { createUvLowLevel(it).getOrNull() } ?: return PackageCollectionPackageStructureNode(emptyList(), emptyList())

    val workspaceTree = buildWorkspaceStructure(uv, declaredPackageNames)
    if (workspaceTree != null) return workspaceTree

    val declaredPackages = declaredPackageNames.map { extractPackageTree(uv, it) }
    val undeclaredPackages = extractUndeclaredPackages(uv, declaredPackageNames)
    return PackageCollectionPackageStructureNode(declaredPackages, undeclaredPackages)
  }

  private suspend fun extractPackageTree(uv: UvLowLevel, packageName: String): PackageNode {
    val output = uv.listPackageRequirementsTree(PythonPackage(packageName, "", false)).getOr {
      return createLeafNode(packageName)
    }
    return parseTree(output.lines())
  }

  private fun createLeafNode(packageName: String): PackageNode =
    PackageNode(PyPackageName.from(packageName))

  private suspend fun buildWorkspaceStructure(uv: UvLowLevel, declaredPackageNames: Set<String>): WorkspaceMemberPackageStructureNode? {
    val (rootName, subMemberNames) = getWorkspaceLayout() ?: return null

    val allMemberNames = (setOf(rootName) + subMemberNames).mapTo(mutableSetOf()) { PyPackageName.from(it).name }

    val rootTree = extractPackageTree(uv, rootName).filterOutMembers(allMemberNames)
    val subMembers = subMemberNames.map { name ->
      WorkspaceMemberPackageStructureNode(name, emptyList(), extractPackageTree(uv, name).filterOutMembers(allMemberNames))
    }

    val shownPackageNames = collectAllPackageNames(rootTree, subMembers)
    val undeclared = extractUndeclaredPackages(uv, declaredPackageNames)
      .filter { it.name.name !in shownPackageNames }

    return WorkspaceMemberPackageStructureNode(rootName, subMembers, rootTree, undeclared)
  }

  private fun PackageNode.filterOutMembers(memberNames: Set<String>): PackageNode {
    val filteredChildren = children
      .filter { it.name.name !in memberNames }
      .map { it.filterOutMembers(memberNames) }
    return PackageNode(name, filteredChildren.toMutableList(), group)
  }

  private fun getWorkspaceLayout(): Pair<String, List<String>>? {
    val workspaceRoot = uvWorkingDirectory ?: return null
    val modules = ModuleManager.getInstance(project).modules
      .filter { it.isPyProjectTomlBased }

    var rootName: String? = null
    val subMemberNames = mutableListOf<String>()

    for (module in modules) {
      val moduleDir = ModuleRootManager.getInstance(module).contentRoots.firstOrNull()?.toNioPath() ?: continue
      when {
        moduleDir == workspaceRoot -> rootName = module.name
        moduleDir.startsWith(workspaceRoot) -> subMemberNames.add(module.name)
      }
    }

    if (rootName == null || subMemberNames.isEmpty()) return null
    return rootName to subMemberNames
  }

  private fun collectAllPackageNames(rootTree: PackageNode?, subMembers: List<WorkspaceMemberPackageStructureNode>): Set<String> {
    val names = mutableSetOf<String>()
    rootTree?.let { collectNamesRecursively(it, names) }
    for (member in subMembers) {
      member.packageTree?.let { collectNamesRecursively(it, names) }
    }
    return names
  }

  private fun collectNamesRecursively(node: PackageNode, result: MutableSet<String>) {
    result.add(node.name.name)
    for (child in node.children) {
      collectNamesRecursively(child, result)
    }
  }

  private suspend fun extractUndeclaredPackages(uv: UvLowLevel?, declaredPackageNames: Set<String>): List<PackageNode> {
    val output = uv?.listAllPackagesTree()?.getOrNull() ?: return emptyList()
    return splitIntoPackageGroups(output.lines()).map { parseTree(it) }
      .filter { it.name.name !in declaredPackageNames }
  }

  private fun splitIntoPackageGroups(lines: List<String>): List<List<String>> {
    val groups = mutableListOf<MutableList<String>>()
    for (line in lines) {
      if (line.isBlank()) continue
      if (TreeParser.isRootLine(line)) {
        groups.add(mutableListOf(line))
      }
      else {
        groups.lastOrNull()?.add(line)
      }
    }
    return groups
  }
}


internal class UvPackageRequirementsTreeExtractorProvider : PythonPackageRequirementsTreeExtractorProvider {
  override fun createExtractor(sdk: Sdk, project: Project): PythonPackageRequirementsTreeExtractor? {
    if (!sdk.isUv) return null
    val data = sdk.sdkAdditionalData as? UvSdkAdditionalData ?: return null
    return UvPackageRequirementsTreeExtractor(data.uvWorkingDirectory, project)
  }
}
