package com.example.cyberdeck.client.gun;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * A compiled Bedrock model: a tree of {@link BedrockPart} bones with cubes attached. Built from
 * {@link BedrockGeoData} using the same pivot/origin/rotation conversion TaCZ uses in
 * {@code BedrockModel.loadNewModel} (Bedrock's y-up, 24-unit-tall convention converted to the
 * Minecraft model space). Rendered by walking the root bones.
 */
public final class BedrockModel {
    private final Map<String, BedrockPart> bonesByName = new HashMap<>();
    private final Map<String, BedrockGeoData.Bone> indexBones = new HashMap<>();
    private final List<BedrockPart> roots = new ArrayList<>();

    public BedrockModel(BedrockGeoData data) {
        int texWidth = data.textureWidth;
        int texHeight = data.textureHeight;

        for (BedrockGeoData.Bone bone : data.bones) {
            indexBones.putIfAbsent(bone.name, bone);
            bonesByName.putIfAbsent(bone.name, new BedrockPart(bone.name));
        }

        for (BedrockGeoData.Bone bone : data.bones) {
            BedrockPart part = bonesByName.get(bone.name);
            part.setPos(convertPivot(bone, 0), convertPivot(bone, 1), convertPivot(bone, 2));
            if (bone.rotation != null) {
                part.setRotation(toRad(bone.rotation[0]), toRad(bone.rotation[1]), toRad(bone.rotation[2]));
            }
            if (bone.parent != null && bonesByName.containsKey(bone.parent)) {
                bonesByName.get(bone.parent).addChild(part);
            } else {
                roots.add(part);
            }

            for (BedrockGeoData.Cube cube : bone.cubes) {
                addCube(part, bone, cube, texWidth, texHeight);
            }
        }
    }

    private void addCube(BedrockPart part, BedrockGeoData.Bone bone, BedrockGeoData.Cube cube,
                         int texWidth, int texHeight) {
        // These leaves are transform markers for Minecraft's player arms. Their authored UVs are
        // dummy values for TaCZ's separate hand renderer and would sample arbitrary gun-atlas
        // pixels here, so retain the animated bone but omit its placeholder cube.
        if (isPlayerArmAnchor(bone.name)) {
            return;
        }
        float sx = cube.size[0];
        float sy = cube.size[1];
        float sz = cube.size[2];
        if (cube.rotation == null) {
            if (cube.faceUv != null) {
                part.cubes.add(BedrockCube.perFace(
                        convertOrigin(bone, cube, 0), convertOrigin(bone, cube, 1), convertOrigin(bone, cube, 2),
                        sx, sy, sz, cube.inflate, texWidth, texHeight, cube.faceUv));
            } else if (cube.boxUv != null) {
                part.cubes.add(BedrockCube.box(cube.boxUv[0], cube.boxUv[1],
                        convertOrigin(bone, cube, 0), convertOrigin(bone, cube, 1), convertOrigin(bone, cube, 2),
                        sx, sy, sz, cube.inflate, cube.mirror, texWidth, texHeight));
            }
            return;
        }
        // Cube-local rotation: wrap the cube in a child part pivoted at the cube pivot.
        BedrockPart cubeNode = new BedrockPart(null);
        cubeNode.setPos(convertPivot(bone, cube, 0), convertPivot(bone, cube, 1), convertPivot(bone, cube, 2));
        cubeNode.setRotation(toRad(cube.rotation[0]), toRad(cube.rotation[1]), toRad(cube.rotation[2]));
        if (cube.faceUv != null) {
            cubeNode.cubes.add(BedrockCube.perFace(
                    convertOrigin(cube, 0), convertOrigin(cube, 1), convertOrigin(cube, 2),
                    sx, sy, sz, cube.inflate, texWidth, texHeight, cube.faceUv));
        } else if (cube.boxUv != null) {
            cubeNode.cubes.add(BedrockCube.box(cube.boxUv[0], cube.boxUv[1],
                    convertOrigin(cube, 0), convertOrigin(cube, 1), convertOrigin(cube, 2),
                    sx, sy, sz, cube.inflate, cube.mirror, texWidth, texHeight));
        }
        part.addChild(cubeNode);
    }

    // --- Conversion math (ported from TaCZ BedrockModel) ---

    private float convertPivot(BedrockGeoData.Bone bone, int index) {
        if (bone.parent != null && indexBones.containsKey(bone.parent)) {
            float[] parentPivot = indexBones.get(bone.parent).pivot;
            if (index == 1) {
                return parentPivot[index] - bone.pivot[index];
            }
            return bone.pivot[index] - parentPivot[index];
        }
        if (index == 1) {
            return 24.0f - bone.pivot[index];
        }
        return bone.pivot[index];
    }

    private float convertPivot(BedrockGeoData.Bone parent, BedrockGeoData.Cube cube, int index) {
        if (index == 1) {
            return parent.pivot[index] - cube.pivot[index];
        }
        return cube.pivot[index] - parent.pivot[index];
    }

    private float convertOrigin(BedrockGeoData.Bone bone, BedrockGeoData.Cube cube, int index) {
        if (index == 1) {
            return bone.pivot[index] - cube.origin[index] - cube.size[index];
        }
        return cube.origin[index] - bone.pivot[index];
    }

    private float convertOrigin(BedrockGeoData.Cube cube, int index) {
        if (index == 1) {
            return cube.pivot[index] - cube.origin[index] - cube.size[index];
        }
        return cube.origin[index] - cube.pivot[index];
    }

    private static float toRad(float degree) {
        return (float) (degree * Math.PI / 180.0);
    }

    public BedrockPart getBone(String name) {
        return bonesByName.get(name);
    }

    public void resetAnimation() {
        for (BedrockPart root : roots) {
            root.resetAnimation();
        }
    }

    public void render(PoseStack poseStack, VertexConsumer consumer, int light, int overlay, int color) {
        for (BedrockPart root : roots) {
            root.render(poseStack, consumer, light, overlay, color);
        }
    }

    public void visitAnimated(PoseStack poseStack, BiConsumer<String, PoseStack> visitor) {
        for (BedrockPart root : roots) {
            root.visitAnimated(poseStack, visitor);
        }
    }

    public static boolean isPlayerArmAnchor(String name) {
        return "righthand_pos".equals(name) || "lefthand_pos".equals(name);
    }
}
