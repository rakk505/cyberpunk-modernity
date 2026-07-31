package com.example.cyberdeck.client.render;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.npc.CityNpc;
import java.util.List;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.Identifier;

/** Player-shaped renderer with civilian variants plus the gold-trimmed mission target. */
public final class CityNpcRenderer
        extends HumanoidMobRenderer<CityNpc, CityNpcRenderState,
                HumanoidModel<CityNpcRenderState>> {
    private static final List<Identifier> SKINS = java.util.stream.IntStream.range(0, CityNpc.SKIN_COUNT)
            .mapToObj(index -> Identifier.fromNamespaceAndPath(Cyberdeck.MODID,
                    "textures/entity/city_npc/corporate_" + index + ".png"))
            .toList();

    public CityNpcRenderer(EntityRendererProvider.Context context) {
        super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER)), 0.45f);
    }

    @Override
    public CityNpcRenderState createRenderState() {
        return new CityNpcRenderState();
    }

    @Override
    public void extractRenderState(CityNpc npc, CityNpcRenderState state, float partialTick) {
        super.extractRenderState(npc, state, partialTick);
        state.skinVariant = npc.getSkinVariant();
    }

    @Override
    public Identifier getTextureLocation(CityNpcRenderState state) {
        return SKINS.get(Math.floorMod(state.skinVariant, SKINS.size()));
    }
}
