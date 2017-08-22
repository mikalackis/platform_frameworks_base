/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "java/ClassDefinition.h"

#include "androidfw/StringPiece.h"

using android::StringPiece;

namespace aapt {

void ClassMember::WriteToStream(const StringPiece& prefix, bool final, std::ostream* out) const {
  processor_.WriteToStream(out, prefix);
}

void MethodDefinition::AppendStatement(const StringPiece& statement) {
  statements_.push_back(statement.to_string());
}

void MethodDefinition::WriteToStream(const StringPiece& prefix, bool final,
                                     std::ostream* out) const {
  *out << prefix << signature_ << " {\n";
  for (const auto& statement : statements_) {
    *out << prefix << "  " << statement << "\n";
  }
  *out << prefix << "}";
}

bool ClassDefinition::empty() const {
  for (const std::unique_ptr<ClassMember>& member : members_) {
    if (!member->empty()) {
      return false;
    }
  }
  return true;
}

void ClassDefinition::WriteToStream(const StringPiece& prefix, bool final,
                                    std::ostream* out) const {
  if (members_.empty() && !create_if_empty_) {
    return;
  }

  ClassMember::WriteToStream(prefix, final, out);

  *out << prefix << "public ";
  if (qualifier_ == ClassQualifier::kStatic) {
    *out << "static ";
  }
  *out << "final class " << name_ << " {\n";

  std::string new_prefix = prefix.to_string();
  new_prefix.append(kIndent);

  for (const std::unique_ptr<ClassMember>& member : members_) {
    member->WriteToStream(new_prefix, final, out);
    *out << "\n";
  }

  *out << prefix << "}";
}

constexpr static const char* sWarningHeader =
    "/* AUTO-GENERATED FILE. DO NOT MODIFY.\n"
    " *\n"
    " * This class was automatically generated by the\n"
    " * aapt tool from the resource data it found. It\n"
    " * should not be modified by hand.\n"
    " */\n\n";

bool ClassDefinition::WriteJavaFile(const ClassDefinition* def,
                                    const StringPiece& package, bool final,
                                    std::ostream* out) {
  *out << sWarningHeader << "package " << package << ";\n\n";
  def->WriteToStream("", final, out);
  return bool(*out);
}

}  // namespace aapt
