import { Button } from "~/components/ui/button";
import type { Route } from "./+types/home";

export function meta({}: Route.MetaArgs) {
  return [
    { title: "New React Router App" },
    { name: "description", content: "Welcome to React Router!" },
  ];
}

export default function Home() {
  return (
    <div className="bg-red-500 flex flex-col gap-4 p-32">
      <Button>测试</Button>
      <Button>测试2</Button>
      <Button>测试3</Button>
    </div>
  );
}
