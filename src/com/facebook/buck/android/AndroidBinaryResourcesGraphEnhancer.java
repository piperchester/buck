/*
 * Copyright 2017-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.android;

import com.facebook.buck.android.aapt.RDotTxtEntry;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.coercer.ManifestEntries;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import org.immutables.value.Value;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Optional;

class AndroidBinaryResourcesGraphEnhancer {
  static final Flavor RESOURCES_FILTER_FLAVOR = InternalFlavor.of("resources_filter");
  static final Flavor AAPT_PACKAGE_FLAVOR = InternalFlavor.of("aapt_package");
  private static final Flavor AAPT2_LINK_FLAVOR = InternalFlavor.of("aapt2_link");
  static final Flavor PACKAGE_STRING_ASSETS_FLAVOR =
      InternalFlavor.of("package_string_assets");
  private static final Flavor MERGE_ASSETS_FLAVOR =
      InternalFlavor.of("merge_assets");

  private final SourcePathRuleFinder ruleFinder;
  private final FilterResourcesStep.ResourceFilter resourceFilter;
  private final ResourcesFilter.ResourceCompressionMode resourceCompressionMode;
  private final ImmutableSet<String> locales;
  private final BuildRuleParams buildRuleParams;
  private final BuildRuleResolver ruleResolver;
  private final SourcePathResolver pathResolver;
  private final AndroidBinary.AaptMode aaptMode;
  private final SourcePath manifest;
  private final Optional<String> resourceUnionPackage;
  private final boolean shouldBuildStringSourceMap;
  private final boolean skipCrunchPngs;
  private final boolean includesVectorDrawables;
  private final EnumSet<RDotTxtEntry.RType> bannedDuplicateResourceTypes;
  private final ManifestEntries manifestEntries;
  private final BuildTarget originalBuildTarget;
  private final Optional<Arg> postFilterResourcesCmd;

  public AndroidBinaryResourcesGraphEnhancer(
      BuildRuleParams buildRuleParams,
      BuildRuleResolver ruleResolver,
      BuildTarget originalBuildTarget,
      SourcePath manifest,
      AndroidBinary.AaptMode aaptMode,
      FilterResourcesStep.ResourceFilter resourceFilter,
      ResourcesFilter.ResourceCompressionMode resourceCompressionMode,
      ImmutableSet<String> locales,
      Optional<String> resourceUnionPackage,
      boolean shouldBuildStringSourceMap,
      boolean skipCrunchPngs,
      boolean includesVectorDrawables,
      EnumSet<RDotTxtEntry.RType> bannedDuplicateResourceTypes,
      ManifestEntries manifestEntries,
      Optional<Arg> postFilterResourcesCmd) {
    this.ruleResolver = ruleResolver;
    this.ruleFinder = new SourcePathRuleFinder(ruleResolver);
    this.pathResolver = new SourcePathResolver(ruleFinder);
    this.resourceFilter = resourceFilter;
    this.resourceCompressionMode = resourceCompressionMode;
    this.locales = locales;
    this.buildRuleParams = buildRuleParams;
    this.aaptMode = aaptMode;
    this.manifest = manifest;
    this.resourceUnionPackage = resourceUnionPackage;
    this.shouldBuildStringSourceMap = shouldBuildStringSourceMap;
    this.skipCrunchPngs = skipCrunchPngs;
    this.includesVectorDrawables = includesVectorDrawables;
    this.bannedDuplicateResourceTypes = bannedDuplicateResourceTypes;
    this.manifestEntries = manifestEntries;
    this.originalBuildTarget = originalBuildTarget;
    this.postFilterResourcesCmd = postFilterResourcesCmd;
  }

  @Value.Immutable
  @BuckStyleImmutable
  interface AbstractAndroidBinaryResourcesGraphEnhancementResult {
    SourcePath getPathToRDotTxt();
    Optional<SourcePath> getRDotJavaDir();
    SourcePath getPrimaryResourcesApkPath();
    SourcePath getAndroidManifestXml();
    SourcePath getAaptGeneratedProguardConfigFile();
    Optional<PackageStringAssets> getPackageStringAssets();
    ImmutableList<BuildRule> getEnhancedDeps();
    ImmutableList<SourcePath> getPrimaryApkAssetZips();
    ImmutableList<SourcePath> getExoResources();
  }

  public AndroidBinaryResourcesGraphEnhancementResult enhance(
      AndroidPackageableCollection packageableCollection) throws NoSuchBuildTargetException {
    ImmutableList.Builder<BuildRule> enhancedDeps = ImmutableList.builder();
    AndroidPackageableCollection.ResourceDetails resourceDetails =
        packageableCollection.getResourceDetails();

    ImmutableSortedSet<BuildRule> resourceRules =
        getTargetsAsRules(resourceDetails.getResourcesWithNonEmptyResDir());

    ImmutableCollection<BuildRule> rulesWithResourceDirectories =
        ruleFinder.filterBuildRuleInputs(resourceDetails.getResourceDirectories());

    FilteredResourcesProvider filteredResourcesProvider;
    boolean needsResourceFiltering = resourceFilter.isEnabled() ||
        resourceCompressionMode.isStoreStringsAsAssets() ||
        !locales.isEmpty();

    if (needsResourceFiltering) {
      ResourcesFilter resourcesFilter = createResourcesFilter(
          resourceDetails,
          resourceRules,
          rulesWithResourceDirectories);
      ruleResolver.addToIndex(resourcesFilter);
      filteredResourcesProvider = resourcesFilter;
      enhancedDeps.add(resourcesFilter);
      resourceRules = ImmutableSortedSet.of(resourcesFilter);
    } else {
      filteredResourcesProvider = new IdentityResourcesProvider(
          resourceDetails.getResourceDirectories().stream()
              .map(sourcePath ->
                  buildRuleParams.getProjectFilesystem()
                      .relativize(pathResolver.getAbsolutePath(sourcePath)))
              .collect(MoreCollectors.toImmutableList()));
    }

    AaptOutputInfo aaptOutputInfo;
    switch (aaptMode) {
      case AAPT1: {
        // Create the AaptPackageResourcesBuildable.
        AaptPackageResources aaptPackageResources = createAaptPackageResources(
            resourceDetails,
            filteredResourcesProvider);
        ruleResolver.addToIndex(aaptPackageResources);
        enhancedDeps.add(aaptPackageResources);
        aaptOutputInfo = aaptPackageResources.getAaptOutputInfo();
      }
      break;

      case AAPT2: {
        Aapt2Link aapt2Link = createAapt2Link(resourceDetails);
        ruleResolver.addToIndex(aapt2Link);
        enhancedDeps.add(aapt2Link);
        aaptOutputInfo = aapt2Link.getAaptOutputInfo();
      }
      break;

      default:
        throw new RuntimeException("Unexpected aaptMode: " + aaptMode);
    }

    Optional<PackageStringAssets> packageStringAssets = Optional.empty();
    ImmutableList.Builder<SourcePath> primaryApkAssetZips = ImmutableList.builder();
    if (resourceCompressionMode.isStoreStringsAsAssets()) {
      packageStringAssets = Optional.of(createPackageStringAssets(
          resourceRules,
          rulesWithResourceDirectories,
          filteredResourcesProvider,
          aaptOutputInfo));
      ruleResolver.addToIndex(packageStringAssets.get());
      enhancedDeps.add(packageStringAssets.get());
      primaryApkAssetZips.add(packageStringAssets.get().getSourcePathToStringAssetsZip());
    }

    MergeAssets mergeAssets =
        createMergeAssetsRule(
            packageableCollection.getAssetsDirectories(),
            aaptOutputInfo.getPrimaryResourcesApkPath());
    ruleResolver.addToIndex(mergeAssets);
    enhancedDeps.add(mergeAssets);

    return AndroidBinaryResourcesGraphEnhancementResult.builder()
        .setAaptGeneratedProguardConfigFile(aaptOutputInfo.getAaptGeneratedProguardConfigFile())
        .setAndroidManifestXml(aaptOutputInfo.getAndroidManifestXml())
        .setPathToRDotTxt(aaptOutputInfo.getPathToRDotTxt())
        .setRDotJavaDir(aaptOutputInfo.getRDotJavaDir())
        .setPrimaryResourcesApkPath(mergeAssets.getSourcePathToOutput())
        .setPrimaryApkAssetZips(primaryApkAssetZips.build())
        .setPackageStringAssets(packageStringAssets)
        .setEnhancedDeps(enhancedDeps.build())
        .setExoResources(ImmutableList.of())
        .build();
  }

  private Aapt2Link createAapt2Link(AndroidPackageableCollection.ResourceDetails resourceDetails)
      throws NoSuchBuildTargetException {
    ImmutableList.Builder<Aapt2Compile> compileListBuilder = ImmutableList.builder();
    for (BuildTarget resTarget : resourceDetails.getResourcesWithNonEmptyResDir()) {
      compileListBuilder.add((Aapt2Compile) ruleResolver.requireRule(
          resTarget.withAppendedFlavors(AndroidResourceDescription.AAPT2_COMPILE_FLAVOR)));
    }
    return new Aapt2Link(
        buildRuleParams
            .withAppendedFlavor(AAPT2_LINK_FLAVOR)
            .copyReplacingDeclaredAndExtraDeps(
                Suppliers.ofInstance(ImmutableSortedSet.of()),
                Suppliers.ofInstance(ImmutableSortedSet.of())),
        ruleFinder,
        compileListBuilder.build(),
        getTargetsAsResourceDeps(resourceDetails.getResourcesWithNonEmptyResDir()),
        manifest,
        manifestEntries,
        resourceUnionPackage,
        bannedDuplicateResourceTypes);
  }

  private ResourcesFilter createResourcesFilter(
      AndroidPackageableCollection.ResourceDetails resourceDetails,
      ImmutableSortedSet<BuildRule> resourceRules,
      ImmutableCollection<BuildRule> rulesWithResourceDirectories) {
    return new ResourcesFilter(
        buildRuleParams
            .withAppendedFlavor(RESOURCES_FILTER_FLAVOR)
            .copyReplacingDeclaredAndExtraDeps(
                Suppliers.ofInstance(
                    ImmutableSortedSet.<BuildRule>naturalOrder()
                        .addAll(resourceRules)
                        .addAll(rulesWithResourceDirectories)
                        .build()),
                Suppliers.ofInstance(ImmutableSortedSet.of())),
        resourceDetails.getResourceDirectories(),
        ImmutableSet.copyOf(resourceDetails.getWhitelistedStringDirectories()),
        locales,
        resourceCompressionMode,
        resourceFilter,
        postFilterResourcesCmd);
  }

  private AaptPackageResources createAaptPackageResources(
      AndroidPackageableCollection.ResourceDetails resourceDetails,
      FilteredResourcesProvider filteredResourcesProvider) {
    return new AaptPackageResources(
        buildRuleParams
            .withAppendedFlavor(AAPT_PACKAGE_FLAVOR)
            .copyReplacingDeclaredAndExtraDeps(
                Suppliers.ofInstance(ImmutableSortedSet.of()),
                Suppliers.ofInstance(ImmutableSortedSet.of())),
        ruleFinder,
        ruleResolver,
        manifest,
        filteredResourcesProvider,
        getTargetsAsResourceDeps(resourceDetails.getResourcesWithNonEmptyResDir()),
        resourceUnionPackage,
        shouldBuildStringSourceMap,
        skipCrunchPngs,
        includesVectorDrawables,
        bannedDuplicateResourceTypes,
        manifestEntries);
  }

  private PackageStringAssets createPackageStringAssets(
      ImmutableSortedSet<BuildRule> resourceRules,
      ImmutableCollection<BuildRule> rulesWithResourceDirectories,
      FilteredResourcesProvider filteredResourcesProvider, AaptOutputInfo aaptOutputInfo) {
    return new PackageStringAssets(
        buildRuleParams
            .withAppendedFlavor(PACKAGE_STRING_ASSETS_FLAVOR)
            .copyReplacingDeclaredAndExtraDeps(
                Suppliers.ofInstance(
                    ImmutableSortedSet.<BuildRule>naturalOrder()
                        .addAll(ruleFinder.filterBuildRuleInputs(aaptOutputInfo.getPathToRDotTxt()))
                        .addAll(resourceRules)
                        .addAll(rulesWithResourceDirectories)
                        // Model the dependency on the presence of res directories, which, in the
                        // case of resource filtering, is cached by the `ResourcesFilter` rule.
                        .addAll(
                            Iterables.filter(
                                ImmutableList.of(filteredResourcesProvider),
                                BuildRule.class))
                        .build()),
                Suppliers.ofInstance(ImmutableSortedSet.of())),
        locales,
        filteredResourcesProvider,
        aaptOutputInfo.getPathToRDotTxt());
  }

  private MergeAssets createMergeAssetsRule(
      ImmutableSet<SourcePath> assetsDirectories,
      SourcePath aaptOutputApk) {
    MergeAssets mergeAssets = new MergeAssets(
        buildRuleParams
            .withAppendedFlavor(MERGE_ASSETS_FLAVOR)
            .copyReplacingDeclaredAndExtraDeps(
                Suppliers.ofInstance(ImmutableSortedSet.of()),
                Suppliers.ofInstance(ImmutableSortedSet.of())),
        ruleFinder,
        aaptOutputApk,
        ImmutableSortedSet.copyOf(assetsDirectories));
    ruleResolver.addToIndex(mergeAssets);
    return mergeAssets;
  }

  private ImmutableSortedSet<BuildRule> getTargetsAsRules(Collection<BuildTarget> buildTargets) {
    return BuildRules.toBuildRulesFor(
        originalBuildTarget,
        ruleResolver,
        buildTargets);
  }


  private ImmutableList<HasAndroidResourceDeps> getTargetsAsResourceDeps(
      Collection<BuildTarget> targets) {
    return getTargetsAsRules(targets).stream()
        .map(input -> {
          Preconditions.checkState(input instanceof HasAndroidResourceDeps);
          return (HasAndroidResourceDeps) input;
        })
        .collect(MoreCollectors.toImmutableList());
  }
}
