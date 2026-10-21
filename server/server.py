from fastapi import FastAPI, UploadFile, File
import shutil
import os

app = FastAPI()

# 确保有个文件夹用来存图片
UPLOAD_DIR = "uploaded_images"
os.makedirs(UPLOAD_DIR, exist_ok=True)

@app.post("/upload")
async def upload_image(file: UploadFile = File(...)):
    """接收平板上传的图片"""
    file_path = os.path.join(UPLOAD_DIR, file.filename)
    # 将上传的文件保存到本地
    with open(file_path, "wb") as buffer:
        shutil.copyfileobj(file.file, buffer)
    return {"code": 200, "message": "上传成功", "filename": file.filename}

if __name__ == "__main__":
    # 监听 0.0.0.0 意味着允许局域网内的其他设备访问
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
