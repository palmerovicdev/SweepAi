"""
Prompt construction for sweep-next-edit-v2.

Adapted from the reference inference.py shipped with the model card.
The plugin sends NextEditAutocompleteRequest fields; we turn those into the
exact prompt format the model expects, compute the prefill, and surface the
indices we need to build the response back.
"""

from dataclasses import dataclass
from typing import List, Optional, Tuple


PROMPT_TEMPLATE = """<|file_sep|>{file_path}
{initial_file}{retrieval_results}
{recent_changes}
<|file_sep|>original/{file_path}:{start_line}:{end_line}
{prev_section}
<|file_sep|>current/{file_path}:{start_line}:{end_line}
{code_block}
<|file_sep|>updated/{file_path}:{start_line}:{end_line}
{prefill}"""

STOP_TOKENS = ["<|endoftext|>", "<|file_sep|>"]
DEFAULT_MAX_NEW_TOKENS = 1024


@dataclass
class FileChunk:
    file_path: str
    content: str

    def to_string(self) -> str:
        return f"<|file_sep|>{self.file_path}\n{self.content}\n"


@dataclass
class BuiltPrompt:
    prompt: str
    code_block: str
    block_start_index: int
    block_end_index: int
    relative_cursor: int
    prefill: str


def compute_prefill(
    code_block: str, relative_cursor: int, changes_above_cursor: bool
) -> str:
    if changes_above_cursor:
        prefill = code_block[:relative_cursor]
        prefilled_lines = prefill.splitlines(True)
        NUM_LINES_ABOVE = 1
        before_split = "".join(prefilled_lines[:NUM_LINES_ABOVE])
        after_split = "".join(prefilled_lines[NUM_LINES_ABOVE:])
        for char in after_split:
            if char == "\n":
                before_split += "\n"
            else:
                break
        return before_split
    else:
        prefix_before_cursor = code_block[:relative_cursor]
        if "\n" not in prefix_before_cursor:
            return ""
        prefill_end = prefix_before_cursor.rfind("\n") + 1
        return code_block[:prefill_end]


def is_pure_insertion_above_cursor(
    code_block: str, completion: str, relative_cursor: int
) -> bool:
    current_line_index = len(code_block[:relative_cursor].splitlines(True))
    code_block_lines = code_block.splitlines(True)
    if current_line_index < 1 or current_line_index > len(code_block_lines):
        return False
    cursor_line = code_block_lines[current_line_index - 1]
    if code_block.strip() == completion.strip():
        return False
    if not cursor_line.strip():
        return False
    prefix_lines = code_block_lines[: current_line_index - 1]
    prefix = "".join(prefix_lines)
    suffix_lines = code_block_lines[current_line_index:]
    suffix = "".join(suffix_lines)
    if completion.startswith(prefix) and completion.endswith(cursor_line + suffix):
        return True
    return False


def build_prompt(
    file_path: str,
    file_contents: str,
    cursor_position: int,
    recent_changes: str = "",
    retrieval_chunks: Optional[List[FileChunk]] = None,
    file_chunks: Optional[List[FileChunk]] = None,
    changes_above_cursor: bool = False,
    num_lines_before: int = 10,
    num_lines_after: int = 10,
) -> BuiltPrompt:
    lines = file_contents.splitlines(True)

    pos = 0
    cursor_line = 0
    for i, line in enumerate(lines):
        if pos + len(line) > cursor_position:
            cursor_line = i
            break
        pos += len(line)
    else:
        cursor_line = max(0, len(lines) - 1)

    block_start = max(0, cursor_line - num_lines_before)
    block_end = min(len(lines), cursor_line + num_lines_after + 1)
    code_block = "".join(lines[block_start:block_end])
    block_start_index = sum(len(l) for l in lines[:block_start])
    block_end_index = block_start_index + len(code_block)

    relative_cursor = max(0, min(len(code_block), cursor_position - block_start_index))

    code_block_with_cursor = (
        code_block[:relative_cursor] + "<|cursor|>" + code_block[relative_cursor:]
    )
    prev_section = code_block

    prefill = compute_prefill(code_block, relative_cursor, changes_above_cursor)

    context_start = max(0, cursor_line - 150)
    context_end = min(len(lines), cursor_line + 150)
    initial_file = "".join(lines[context_start:context_end])

    retrieval_results = ""
    if retrieval_chunks:
        retrieval_results = "".join(
            f"\n{chunk.to_string()}" for chunk in retrieval_chunks
        )

    start_line = block_start + 1
    end_line = block_end

    formatted = PROMPT_TEMPLATE.format(
        file_path=file_path,
        initial_file=initial_file,
        retrieval_results=retrieval_results,
        recent_changes=recent_changes,
        prev_section=prev_section,
        code_block=code_block_with_cursor,
        start_line=start_line,
        end_line=end_line,
        prefill=prefill,
    )

    if file_chunks:
        formatted = "".join(c.to_string() for c in file_chunks) + formatted

    return BuiltPrompt(
        prompt=formatted,
        code_block=code_block,
        block_start_index=block_start_index,
        block_end_index=block_end_index,
        relative_cursor=relative_cursor,
        prefill=prefill,
    )
