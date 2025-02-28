// This file is made available under Elastic License 2.0.
// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/be/src/olap/field.h

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

#ifndef STARROCKS_BE_SRC_OLAP_FIELD_H
#define STARROCKS_BE_SRC_OLAP_FIELD_H

#include <sstream>
#include <string>

#include "runtime/mem_pool.h"
#include "storage/aggregate_func.h"
#include "storage/decimal_type_info.h"
#include "storage/key_coder.h"
#include "storage/olap_common.h"
#include "storage/olap_define.h"
#include "storage/row_cursor_cell.h"
#include "storage/tablet_schema.h"
#include "storage/types.h"
#include "storage/utils.h"
#include "util/hash_util.hpp"
#include "util/mem_util.hpp"
#include "util/slice.h"

namespace starrocks {

// A Field is used to represent a column in memory format.
// User can use this class to access or deal with column data in memory.
class Field {
public:
    explicit Field() = default;
    explicit Field(const TabletColumn& column)
            : _name(column.name()),
              _type_info(get_type_info(column)),
              _key_coder(get_key_coder(column.type())),
              _agg_info(get_aggregate_info(column.aggregation(), column.type())),
              _index_size(column.index_length()),
              _length(column.length()),
              _is_nullable(column.is_nullable()) {
        DCHECK(column.type() != OLAP_FIELD_TYPE_DECIMAL32 && column.type() != OLAP_FIELD_TYPE_DECIMAL64 &&
               column.type() != OLAP_FIELD_TYPE_DECIMAL128);
    }

    Field(const TabletColumn column, std::shared_ptr<TypeInfo>&& type_info)
            : _name(column.name()),
              _type_info(type_info),
              _key_coder(get_key_coder(column.type())),
              _agg_info(get_aggregate_info(column.aggregation(), column.type())),
              _index_size(column.index_length()),
              _length(column.length()),
              _is_nullable(column.is_nullable()) {}

    virtual ~Field() = default;

    // Disable copy ctor and assignment.
    Field(const Field&) = delete;
    void operator=(const Field&) = delete;

    // Enable move ctor and move assignment.
    Field(Field&&) = default;
    Field& operator=(Field&&) = default;

    size_t size() const { return _type_info->size(); }
    int32_t length() const { return _length; }
    size_t field_size() const { return size() + 1; }
    size_t index_size() const { return _index_size; }
    const std::string& name() const { return _name; }

    virtual void set_to_max(char* buf) const { return _type_info->set_to_max(buf); }
    void set_to_min(char* buf) const { return _type_info->set_to_min(buf); }

    // This function allocate memory from pool, other than allocate_memory
    // reserve memory from continuous memory.
    virtual char* allocate_value(MemPool* pool) const { return (char*)pool->allocate(_type_info->size()); }

    void agg_update(RowCursorCell* dest, const RowCursorCell& src, MemPool* mem_pool = nullptr) const {
        _agg_info->update(dest, src, mem_pool);
    }

    void agg_finalize(RowCursorCell* dst, MemPool* mem_pool) const { _agg_info->finalize(dst, mem_pool); }

    virtual void consume(RowCursorCell* dst, const char* src, bool src_null, MemPool* mem_pool,
                         ObjectPool* agg_pool) const {
        _agg_info->init(dst, src, src_null, mem_pool, agg_pool);
    }

    // todo(kks): Unify AggregateInfo::init method and Field::agg_init method

    // This function will initialize destination with source.
    // This functionn differs copy functionn in that if this field
    // contain aggregate information, this functionn will initialize
    // destination in aggregate format, and update with srouce content.
    virtual void agg_init(RowCursorCell* dst, const RowCursorCell& src, MemPool* mem_pool, ObjectPool* agg_pool) const {
        direct_copy(dst, src, mem_pool);
    }

    virtual char* allocate_memory(char* cell_ptr, char* variable_ptr) const { return variable_ptr; }

    virtual size_t get_variable_len() const { return 0; }

    virtual Field* clone() const {
        auto* local = new Field();
        this->clone(local);
        return local;
    }

    // Test if these two cell is equal with each other
    template <typename LhsCellType, typename RhsCellType>
    bool equal(const LhsCellType& lhs, const RhsCellType& rhs) const {
        bool l_null = lhs.is_null();
        bool r_null = rhs.is_null();

        if (l_null != r_null) {
            return false;
        } else if (l_null) {
            return true;
        } else {
            return _type_info->equal(lhs.cell_ptr(), rhs.cell_ptr());
        }
    }

    // Only compare column content, without considering NULL condition.
    // RETURNS:
    //      0 means equal,
    //      -1 means left less than rigth,
    //      1 means left bigger than right
    int compare(const void* left, const void* right) const { return _type_info->cmp(left, right); }

    // Compare two types of cell.
    // This function differs compare in that this function compare cell which
    // will consider the condition which cell may be NULL. While compare only
    // compare column content without considering NULL condition.
    // Only compare column content, without considering NULL condition.
    // RETURNS:
    //      0 means equal,
    //      -1 means left less than rigth,
    //      1 means left bigger than right
    template <typename LhsCellType, typename RhsCellType>
    int compare_cell(const LhsCellType& lhs, const RhsCellType& rhs) const {
        bool l_null = lhs.is_null();
        bool r_null = rhs.is_null();
        if (l_null != r_null) {
            return l_null ? -1 : 1;
        }
        return l_null ? 0 : _type_info->cmp(lhs.cell_ptr(), rhs.cell_ptr());
    }

    // Used to compare short key index. Because short key will truncate
    // a varchar column, this function will handle in this condition.
    template <typename LhsCellType, typename RhsCellType>
    inline int index_cmp(const LhsCellType& lhs, const RhsCellType& rhs) const;

    // Copy source cell's content to destination cell directly.
    // For string type, this function assume that destination has
    // enough space and copy source content into destination without
    // memory allocation.
    template <typename DstCellType, typename SrcCellType>
    void direct_copy(DstCellType* dst, const SrcCellType& src, MemPool* pool = nullptr) const {
        bool is_null = src.is_null();
        dst->set_is_null(is_null);
        if (is_null) {
            return;
        }
        return _type_info->direct_copy(dst->mutable_cell_ptr(), src.cell_ptr(), pool);
    }

    // deep copy source cell' content to destination cell.
    // For string type, this will allocate data form pool,
    // and copy srouce's conetent.
    template <typename DstCellType, typename SrcCellType>
    void copy_object(DstCellType* dst, const SrcCellType& src, MemPool* pool) const {
        bool is_null = src.is_null();
        dst->set_is_null(is_null);
        if (is_null) {
            return;
        }
        _type_info->copy_object(dst->mutable_cell_ptr(), src.cell_ptr(), pool);
    }

    // deep copy source cell' content to destination cell.
    // For string type, this will allocate data form pool,
    // and copy srouce's conetent.
    template <typename DstCellType, typename SrcCellType>
    void deep_copy(DstCellType* dst, const SrcCellType& src, MemPool* pool) const {
        bool is_null = src.is_null();
        dst->set_is_null(is_null);
        if (is_null) {
            return;
        }
        _type_info->deep_copy(dst->mutable_cell_ptr(), src.cell_ptr(), pool);
    }

    // deep copy field content from `src` to `dst` without null-byte
    void deep_copy_content(char* dst, const char* src, MemPool* mem_pool) const {
        _type_info->deep_copy(dst, src, mem_pool);
    }

    // shallow copy field content from `src` to `dst` without null-byte.
    // for string like type, shallow copy only copies Slice, not the actual data pointed by slice.
    void shallow_copy_content(char* dst, const char* src) const { _type_info->shallow_copy(dst, src); }

    //convert and copy field from src to desc
    OLAPStatus convert_from(char* dest, const char* src, const TypeInfoPtr& src_type, MemPool* mem_pool) const {
        return _type_info->convert_from(dest, src, src_type, mem_pool);
    }

    // Copy srouce content to destination in index format.
    template <typename DstCellType, typename SrcCellType>
    void to_index(DstCellType* dst, const SrcCellType& src) const;

    // used by init scan key stored in string format
    // value_string should end with '\0'
    OLAPStatus from_string(char* buf, const std::string& value_string) const {
        return _type_info->from_string(buf, value_string);
    }

    // It's a critical function, used by ZoneMapIndexWriter to serialize max and min value
    std::string to_string(const char* src) const { return _type_info->to_string(src); }

    template <typename CellType>
    std::string debug_string(const CellType& cell) const {
        std::stringstream ss;
        if (cell.is_null()) {
            ss << "(null)";
        } else {
            ss << _type_info->to_string(cell.cell_ptr());
        }
        return ss.str();
    }

    template <typename CellType>
    uint32_t hash_code(const CellType& cell, uint32_t seed) const;

    FieldType type() const { return _type_info->type(); }
    FieldAggregationMethod aggregation() const { return _agg_info->agg_method(); }
    const TypeInfoPtr& type_info() const { return _type_info; }
    bool is_nullable() const { return _is_nullable; }

    // similar to `full_encode_ascending`, but only encode part (the first `index_size` bytes) of the value.
    // only applicable to string type
    void encode_ascending(const void* value, std::string* buf) const {
        _key_coder->encode_ascending(value, _index_size, buf);
    }

    // encode the provided `value` into `buf`.
    void full_encode_ascending(const void* value, std::string* buf) const {
        _key_coder->full_encode_ascending(value, buf);
    }

    Status decode_ascending(Slice* encoded_key, uint8_t* cell_ptr, MemPool* pool) const {
        return _key_coder->decode_ascending(encoded_key, _index_size, cell_ptr, pool);
    }

    std::string to_zone_map_string(const char* value) const {
        switch (type()) {
        case OLAP_FIELD_TYPE_DECIMAL32: {
            auto* decimal_type_info = down_cast<DecimalTypeInfo<OLAP_FIELD_TYPE_DECIMAL32>*>(type_info().get());
            return decimal_type_info->to_zone_map_string(value);
        }
        case OLAP_FIELD_TYPE_DECIMAL64: {
            auto* decimal_type_info = down_cast<DecimalTypeInfo<OLAP_FIELD_TYPE_DECIMAL64>*>(type_info().get());
            return decimal_type_info->to_zone_map_string(value);
        }
        case OLAP_FIELD_TYPE_DECIMAL128: {
            auto* decimal_type_info = down_cast<DecimalTypeInfo<OLAP_FIELD_TYPE_DECIMAL128>*>(type_info().get());
            return decimal_type_info->to_zone_map_string(value);
        }
        default: {
            return type_info()->to_string(value);
        }
        }
    }

    void add_sub_field(std::unique_ptr<Field> sub_field) { _sub_fields.emplace_back(std::move(sub_field)); }

    Field* get_sub_field(int i) { return _sub_fields[i].get(); }

    Status convert_to(FieldType type, std::unique_ptr<Field>* output) const {
        std::unique_ptr<Field> new_field(clone());
        new_field->_type_info = get_type_info(type);
        new_field->_key_coder = get_key_coder(type);

        // TODO(zc): we only support fixed length type now.
        new_field->_index_size = new_field->_type_info->size();

        *output = std::move(new_field);
        return Status::OK();
    }

    virtual std::string debug_string() const {
        std::stringstream ss;
        ss << "(type=" << _type_info->type() << ",index_size=" << _index_size << ",is_nullable=" << _is_nullable
           << ",aggregation=" << _agg_info->agg_method() << ",length=" << _length << ")";
        return ss.str();
    }

protected:
    char* allocate_string_value(MemPool* pool) const {
        char* type_value = (char*)pool->allocate(sizeof(Slice));
        auto slice = reinterpret_cast<Slice*>(type_value);
        slice->size = _length;
        slice->data = (char*)pool->allocate(slice->size);
        return type_value;
    }

    void clone(Field* other) const {
        other->_type_info = this->_type_info;
        other->_key_coder = this->_key_coder;
        other->_name = this->_name;
        other->_index_size = this->_index_size;
        other->_is_nullable = this->_is_nullable;
        other->_sub_fields.clear();
        for (const auto& f : _sub_fields) {
            Field* item = f->clone();
            other->add_sub_field(std::unique_ptr<Field>(item));
        }
    }

    std::string _name;
    TypeInfoPtr _type_info;
    const KeyCoder* _key_coder;
    const AggregateInfo* _agg_info;
    uint16_t _index_size;
    uint32_t _length;
    bool _is_nullable;
    std::vector<std::unique_ptr<Field>> _sub_fields;
};

template <typename LhsCellType, typename RhsCellType>
int Field::index_cmp(const LhsCellType& lhs, const RhsCellType& rhs) const {
    bool l_null = lhs.is_null();
    bool r_null = rhs.is_null();
    if (l_null != r_null) {
        return l_null ? -1 : 1;
    } else if (l_null) {
        return 0;
    }

    int32_t res = 0;
    if (type() == OLAP_FIELD_TYPE_VARCHAR) {
        const Slice* l_slice = reinterpret_cast<const Slice*>(lhs.cell_ptr());
        const Slice* r_slice = reinterpret_cast<const Slice*>(rhs.cell_ptr());

        if (r_slice->size + OLAP_STRING_MAX_BYTES > _index_size ||
            l_slice->size + OLAP_STRING_MAX_BYTES > _index_size) {
            // if field length is larger than short key, only compare prefix to make sure that
            // the same short key block will be scanned.
            int compare_size = _index_size - OLAP_STRING_MAX_BYTES;
            // l_slice size and r_slice size may be less than compare_size
            // so calculate the min of the three size as new compare_size
            compare_size = std::min(std::min(compare_size, (int)l_slice->size), (int)r_slice->size);

            // This functionn is used to compare prefix index.
            // Only the fixed length of prefix index should be compared.
            // If r_slice->size > l_slice->size, igonre the extra parts directly.
            res = strncmp(l_slice->data, r_slice->data, compare_size);
            if (res == 0 && compare_size != (_index_size - OLAP_STRING_MAX_BYTES)) {
                if (l_slice->size < r_slice->size) {
                    res = -1;
                } else if (l_slice->size > r_slice->size) {
                    res = 1;
                } else {
                    res = 0;
                }
            }
        } else {
            res = l_slice->compare(*r_slice);
        }
    } else {
        res = _type_info->cmp(lhs.cell_ptr(), rhs.cell_ptr());
    }

    return res;
}

template <typename DstCellType, typename SrcCellType>
void Field::to_index(DstCellType* dst, const SrcCellType& src) const {
    bool is_null = src.is_null();
    dst->set_is_null(is_null);
    if (is_null) {
        return;
    }

    if (type() == OLAP_FIELD_TYPE_VARCHAR) {
        memset(dst->mutable_cell_ptr(), 0, _index_size);
        const Slice* slice = reinterpret_cast<const Slice*>(src.cell_ptr());
        size_t copy_size =
                slice->size < _index_size - OLAP_STRING_MAX_BYTES ? slice->size : _index_size - OLAP_STRING_MAX_BYTES;
        *reinterpret_cast<StringLengthType*>(dst->mutable_cell_ptr()) = copy_size;
        memory_copy((char*)dst->mutable_cell_ptr() + OLAP_STRING_MAX_BYTES, slice->data, copy_size);
    } else if (type() == OLAP_FIELD_TYPE_CHAR) {
        memset(dst->mutable_cell_ptr(), 0, _index_size);
        const Slice* slice = reinterpret_cast<const Slice*>(src.cell_ptr());
        memory_copy(dst->mutable_cell_ptr(), slice->data, _index_size);
    } else {
        memory_copy(dst->mutable_cell_ptr(), src.cell_ptr(), size());
    }
}

template <typename CellType>
uint32_t Field::hash_code(const CellType& cell, uint32_t seed) const {
    bool is_null = cell.is_null();
    if (is_null) {
        return HashUtil::hash(&is_null, sizeof(is_null), seed);
    }
    return _type_info->hash_code(cell.cell_ptr(), seed);
}

class CharField : public Field {
public:
    explicit CharField() : Field() {}
    explicit CharField(const TabletColumn& column) : Field(column) {}

    // the char field is especial, which need the _length info when consume raw data
    void consume(RowCursorCell* dst, const char* src, bool src_null, MemPool* mem_pool,
                 ObjectPool* agg_pool) const override {
        dst->set_is_null(src_null);
        if (src_null) {
            return;
        }

        auto* value = reinterpret_cast<const StringValue*>(src);
        auto* dest_slice = (Slice*)(dst->mutable_cell_ptr());
        dest_slice->size = _length;
        dest_slice->data = (char*)mem_pool->allocate(dest_slice->size);
        memcpy(dest_slice->data, value->ptr, value->len);
        memset(dest_slice->data + value->len, 0, dest_slice->size - value->len);
    }

    size_t get_variable_len() const override { return _length; }

    char* allocate_memory(char* cell_ptr, char* variable_ptr) const override {
        auto slice = (Slice*)cell_ptr;
        slice->data = variable_ptr;
        slice->size = _length;
        variable_ptr += slice->size;
        return variable_ptr;
    }

    CharField* clone() const override {
        auto* local = new CharField();
        Field::clone(local);
        return local;
    }

    char* allocate_value(MemPool* pool) const override { return Field::allocate_string_value(pool); }

    void set_to_max(char* ch) const override {
        auto slice = reinterpret_cast<Slice*>(ch);
        slice->size = _length;
        memset(slice->data, 0xFF, slice->size);
    }
};

class VarcharField : public Field {
public:
    explicit VarcharField() : Field() {}
    explicit VarcharField(const TabletColumn& column) : Field(column) {}

    size_t get_variable_len() const override { return _length - OLAP_STRING_MAX_BYTES; }

    // minus OLAP_STRING_MAX_BYTES here just for being compatible with old storage format
    char* allocate_memory(char* cell_ptr, char* variable_ptr) const override {
        auto slice = (Slice*)cell_ptr;
        slice->data = variable_ptr;
        slice->size = _length - OLAP_STRING_MAX_BYTES;
        variable_ptr += slice->size;
        return variable_ptr;
    }

    VarcharField* clone() const override {
        auto* local = new VarcharField();
        Field::clone(local);
        return local;
    }

    char* allocate_value(MemPool* pool) const override { return Field::allocate_string_value(pool); }

    void set_to_max(char* ch) const override {
        auto slice = reinterpret_cast<Slice*>(ch);
        slice->size = _length - OLAP_STRING_MAX_BYTES;
        memset(slice->data, 0xFF, slice->size);
    }
};

class BitmapAggField : public Field {
public:
    explicit BitmapAggField() : Field() {}
    explicit BitmapAggField(const TabletColumn& column) : Field(column) {}

    // bitmap storage data always not null
    void agg_init(RowCursorCell* dst, const RowCursorCell& src, MemPool* mem_pool,
                  ObjectPool* agg_pool) const override {
        _agg_info->init(dst, (const char*)src.cell_ptr(), src.is_null(), mem_pool, agg_pool);
    }

    char* allocate_memory(char* cell_ptr, char* variable_ptr) const override {
        auto slice = (Slice*)cell_ptr;
        slice->data = nullptr;
        return variable_ptr;
    }

    BitmapAggField* clone() const override {
        auto* local = new BitmapAggField();
        Field::clone(local);
        return local;
    }
};

class HllAggField : public Field {
public:
    explicit HllAggField() : Field() {}
    explicit HllAggField(const TabletColumn& column) : Field(column) {}

    // Hll storage data always not null
    void agg_init(RowCursorCell* dst, const RowCursorCell& src, MemPool* mem_pool,
                  ObjectPool* agg_pool) const override {
        _agg_info->init(dst, (const char*)src.cell_ptr(), false, mem_pool, agg_pool);
    }

    char* allocate_memory(char* cell_ptr, char* variable_ptr) const override {
        auto slice = (Slice*)cell_ptr;
        slice->data = nullptr;
        return variable_ptr;
    }

    HllAggField* clone() const override {
        auto* local = new HllAggField();
        Field::clone(local);
        return local;
    }
};

class PercentileAggField : public Field {
public:
    PercentileAggField() : Field() {}
    explicit PercentileAggField(const TabletColumn& column) : Field(column) {}

    // Hll storage data always not null
    void agg_init(RowCursorCell* dst, const RowCursorCell& src, MemPool* mem_pool,
                  ObjectPool* agg_pool) const override {
        _agg_info->init(dst, (const char*)src.cell_ptr(), false, mem_pool, agg_pool);
    }

    char* allocate_memory(char* cell_ptr, char* variable_ptr) const override {
        auto slice = (Slice*)cell_ptr;
        slice->data = nullptr;
        return variable_ptr;
    }

    PercentileAggField* clone() const override {
        auto* local = new PercentileAggField();
        Field::clone(local);
        return local;
    }
};

class FieldFactory {
public:
    static Field* create(const TabletColumn& column) {
        // for key column
        if (column.is_key()) {
            switch (column.type()) {
            case OLAP_FIELD_TYPE_CHAR:
                return new CharField(column);
            case OLAP_FIELD_TYPE_VARCHAR:
                return new VarcharField(column);
            case OLAP_FIELD_TYPE_ARRAY: {
                std::unique_ptr<Field> item_field(FieldFactory::create(column.get_sub_column(0)));
                auto* local = new Field(column);
                local->add_sub_field(std::move(item_field));
                return local;
            }
            case OLAP_FIELD_TYPE_DECIMAL32:
                return new Field(column, std::make_shared<DecimalTypeInfo<OLAP_FIELD_TYPE_DECIMAL32>>(
                                                 column.precision(), column.scale()));
            case OLAP_FIELD_TYPE_DECIMAL64:
                return new Field(column, std::make_shared<DecimalTypeInfo<OLAP_FIELD_TYPE_DECIMAL64>>(
                                                 column.precision(), column.scale()));
            case OLAP_FIELD_TYPE_DECIMAL128:
                return new Field(column, std::make_shared<DecimalTypeInfo<OLAP_FIELD_TYPE_DECIMAL128>>(
                                                 column.precision(), column.scale()));
            default:
                return new Field(column);
            }
        }

        // for value column
        switch (column.aggregation()) {
        case OLAP_FIELD_AGGREGATION_NONE:
        case OLAP_FIELD_AGGREGATION_SUM:
        case OLAP_FIELD_AGGREGATION_MIN:
        case OLAP_FIELD_AGGREGATION_MAX:
        case OLAP_FIELD_AGGREGATION_REPLACE:
        case OLAP_FIELD_AGGREGATION_REPLACE_IF_NOT_NULL:
            switch (column.type()) {
            case OLAP_FIELD_TYPE_CHAR:
                return new CharField(column);
            case OLAP_FIELD_TYPE_VARCHAR:
                return new VarcharField(column);
            case OLAP_FIELD_TYPE_ARRAY: {
                std::unique_ptr<Field> item_field(FieldFactory::create(column.get_sub_column(0)));
                auto* local = new Field(column);
                local->add_sub_field(std::move(item_field));
                return local;
            }
            case OLAP_FIELD_TYPE_DECIMAL32:
                return new Field(column, std::make_shared<DecimalTypeInfo<OLAP_FIELD_TYPE_DECIMAL32>>(
                                                 column.precision(), column.scale()));
            case OLAP_FIELD_TYPE_DECIMAL64:
                return new Field(column, std::make_shared<DecimalTypeInfo<OLAP_FIELD_TYPE_DECIMAL64>>(
                                                 column.precision(), column.scale()));
            case OLAP_FIELD_TYPE_DECIMAL128:
                return new Field(column, std::make_shared<DecimalTypeInfo<OLAP_FIELD_TYPE_DECIMAL128>>(
                                                 column.precision(), column.scale()));
            default:
                return new Field(column);
            }
        case OLAP_FIELD_AGGREGATION_HLL_UNION:
            return new HllAggField(column);
        case OLAP_FIELD_AGGREGATION_BITMAP_UNION:
            return new BitmapAggField(column);
        case OLAP_FIELD_AGGREGATION_PERCENTILE_UNION:
            return new PercentileAggField(column);
        case OLAP_FIELD_AGGREGATION_UNKNOWN:
            LOG(WARNING) << "WOW! value column agg type is unknown";
            return nullptr;
        }
        LOG(WARNING) << "WOW! value column no agg type";
        return nullptr;
    }

    static Field* create_by_type(const FieldType& type) {
        TabletColumn column(OLAP_FIELD_AGGREGATION_NONE, type);
        return create(column);
    }
};

} // namespace starrocks

#endif // STARROCKS_BE_SRC_OLAP_FIELD_H
