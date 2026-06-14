import { AppShell } from "@/components/shell/AppShell";
import { PageHeading } from "@/components/shell/PageHeading";
import { SettingsView } from "@/components/settings/SettingsView";
import {
  getConnectedSources,
  getNotificationChannels,
  getPolicyRules,
  getSuppressions,
} from "@/lib/mock-settings";

export default async function SettingsPage() {
  const [sources, channels, policyRules, suppressions] = await Promise.all([
    getConnectedSources(),
    getNotificationChannels(),
    getPolicyRules(),
    getSuppressions(),
  ]);

  return (
    <AppShell crumbKey="nav.settings">
      <PageHeading
        titleKey="page.settings.title"
        subtitleKey="page.settings.subtitle"
      />
      <SettingsView
        sources={sources}
        channels={channels}
        policyRules={policyRules}
        suppressions={suppressions}
      />
    </AppShell>
  );
}
