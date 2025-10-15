package net.shaddii.smartsorter.datagen;

//? if >= 1.21.8 {

import net.fabricmc.fabric.api.client.datagen.v1.provider.FabricModelProvider;
import net.fabricmc.fabric.api.datagen.v1.FabricDataOutput;
import net.minecraft.client.data.BlockStateModelGenerator;
import net.minecraft.client.data.ItemModelGenerator;
import net.minecraft.client.data.Models;
import net.shaddii.smartsorter.SmartSorter;


 //Model provider for SmartSorter - generates block states and item models
 //This is the proper way to handle models in Minecraft 1.21.9

public class SmartSorterModelProvider extends FabricModelProvider {
    
    public SmartSorterModelProvider(FabricDataOutput output) {
        super(output);
    }

    @Override
    public void generateBlockStateModels(BlockStateModelGenerator generator) {
        // Storage Controller - simple block with no rotation
        generator.registerSimpleCubeAll(SmartSorter.STORAGE_CONTROLLER_BLOCK);
        
        // Intake Block - has FACING property, needs rotation variants
        generator.registerSimpleState(SmartSorter.INTAKE_BLOCK);

        // Output Probe - has FACING property, needs rotation variants
        generator.registerSimpleState(SmartSorter.PROBE_BLOCK);

    }

    @Override
    public void generateItemModels(ItemModelGenerator generator) {
        // Linking Tool - standalone item (not a block)
        generator.register(SmartSorter.LINKING_TOOL, Models.GENERATED);
        
        // Note: Block items (INTAKE_ITEM, PROBE_ITEM, STORAGE_CONTROLLER_ITEM) 
        // are automatically generated from their block models by the BlockStateModelGenerator
    }
    //?} else {
/*import net.fabricmc.fabric.api.datagen.v1.FabricDataOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricModelProvider;
import net.minecraft.data.client.BlockStateModelGenerator;
import net.minecraft.data.client.ItemModelGenerator;
import net.minecraft.data.client.Models;
import net.shaddii.smartsorter.SmartSorter;

/^*
 * Model provider for SmartSorter - generates block states and item models
 ^/
public class SmartSorterModelProvider extends FabricModelProvider {

    public SmartSorterModelProvider(FabricDataOutput output) {
        super(output);
    }

    @Override
    public void generateBlockStateModels(BlockStateModelGenerator generator) {
        // Storage Controller - simple block with no rotation
        generator.registerSimpleCubeAll(SmartSorter.STORAGE_CONTROLLER_BLOCK);

        // Intake Block - has FACING property, needs rotation variants
        generator.registerNorthDefaultHorizontalRotation(SmartSorter.INTAKE_BLOCK);

        // Output Probe - has FACING property, needs rotation variants
        generator.registerNorthDefaultHorizontalRotation(SmartSorter.PROBE_BLOCK);

    }

    @Override
    public void generateItemModels(ItemModelGenerator generator) {
        // Linking Tool - standalone item (not a block)
        generator.register(SmartSorter.LINKING_TOOL, Models.GENERATED);

        // Note: Block items are automatically generated from their block models
    }
    *///?}
}
