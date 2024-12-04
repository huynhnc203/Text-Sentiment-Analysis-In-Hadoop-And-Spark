# Text Sentiment Analysis In Hadoop & Spark

| MSV       | Thành viên          |
|-----------|---------------------|
| 22022565  | Nguyễn Công Huynh   |
| 22022xxx  | Ngô Đức Hùng        |
| 22022xxx  | Nguyễn Văn Trường   |

## Triển khai

Input: csv (tweet id, text, timestamp, sentiment_label, ...)

Công cụ sử dụng: Hadoop(HDFS), Spark.

Luồng xử lý:

1. Lưu dữ liệu lên HDFS (hadoop).
2. Lấy dữ liệu từ HDFS nạp nào Spark.
3. Xử lý dữ liệu (ETL).
4. Tokenize và xử lý văn bản.
5. Vectorization (TF-IDF, Word2Vec).
6. Huấn luyện mô hình.
7. Dự đoán và đánh giá kết quả.
8. Lưu kq vô SparkSQL phù hợp cho truy vấn.
