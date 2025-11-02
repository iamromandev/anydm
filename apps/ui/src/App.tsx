import { useState } from "react"; 
 
function App() {
  const [url, setUrl] = useState("");
  const [message, setMessage] = useState("");

  const handleDownload = async () => {
    if (!url) return setMessage("Please enter a URL.");

    try {
      const res = await fetch("http://localhost:3001/download", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ url }),
      });

      if (!res.ok) throw new Error("Failed to download");

      const data = await res.blob();
      const a = document.createElement("a");
      a.href = URL.createObjectURL(data);
      a.download = "video.mp4";
      a.click();

      setMessage("Download started!");
    } catch (err: any) {
      setMessage(err.message);
    }
  };


  return (
    <div style={{ padding: 20, fontFamily: "sans-serif" }}>
      <h1>AnyDM YouTube Downloader for </h1>
      <input
        type="text"
        placeholder="Enter YouTube URL"
        value={url}
        onChange={(e) => setUrl(e.target.value)}
        style={{ width: "400px", marginRight: "10px", padding: "5px" }}
      />
      <button onClick={handleDownload}>Download</button>
      <p>{message}</p>
    </div>
  );
}

export default App;