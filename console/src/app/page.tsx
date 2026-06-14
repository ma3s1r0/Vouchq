import { redirect } from "next/navigation";

export default function Home() {
  // No real auth/session yet — send everyone to the placeholder login.
  redirect("/login");
}
