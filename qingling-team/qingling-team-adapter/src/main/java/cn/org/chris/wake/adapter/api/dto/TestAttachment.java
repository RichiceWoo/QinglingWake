package cn.org.chris.wake.adapter.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 描述 TestAPI 从本机复制到 session workspace 的附件。
 *
 * @param filePath 本地源文件路径
 * @param fileName 可选目标文件名；为空时使用源文件名
 */
public record TestAttachment(
        /** 本地源文件路径，不是飞书 file_key。 */ @JsonProperty("file_path") String filePath,
        /** 可选目标文件名。 */ @JsonProperty("file_name") String fileName
) {
}
