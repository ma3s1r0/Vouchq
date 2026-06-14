import { AppShell } from "@/components/shell/AppShell";
import { PageHeading } from "@/components/shell/PageHeading";
import { InventoryView } from "@/components/inventory/InventoryView";
import { getInventory } from "@/lib/mock-inventory";

export default async function InventoryPage() {
  const items = await getInventory();

  return (
    <AppShell crumb="Inventory">
      <PageHeading
        titleKey="page.inventory.title"
        subtitleKey="page.inventory.subtitle"
      />
      <InventoryView items={items} />
    </AppShell>
  );
}
