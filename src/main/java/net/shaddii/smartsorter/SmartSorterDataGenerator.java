package net.shaddii.smartsorter;

import net.fabricmc.fabric.api.datagen.v1.DataGeneratorEntrypoint;
import net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator;
import net.shaddii.smartsorter.datagen.SmartSorterModelProvider;

public class SmartSorterDataGenerator implements DataGeneratorEntrypoint {
	@Override
	public void onInitializeDataGenerator(FabricDataGenerator fabricDataGenerator) {
		FabricDataGenerator.Pack pack = fabricDataGenerator.createPack();
		
		// Register model provider for automatic block state and item model generation
		pack.addProvider(SmartSorterModelProvider::new);
	}
}
