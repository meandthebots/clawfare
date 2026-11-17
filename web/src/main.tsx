import { createRoot } from "react-dom/client";
import { App } from "./App";
import "./legacy.css";

const root = document.getElementById("root");
if (!root) throw new Error("Clawfare root missing");
createRoot(root).render(<App />);
